package easyshare.messenger;

import easyshare.common.DaemonThreads;
import easyshare.common.HashUtil;
import easyshare.common.Log;
import easyshare.common.NetUtil;
import easyshare.common.PeerAddress;
import easyshare.common.Protocol;
import easyshare.meta.FileMeta;
import easyshare.peer.Download;
import easyshare.peer.PeerConfig;
import easyshare.peer.PeerNode;
import easyshare.peer.SharedFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * IP Messenger-style service: finds other computers on the LAN automatically, sends messages
 * with attached files/folders, and transfers accepted files with the EasyShare P2P engine
 * (256 KB pieces, SHA-1 check of every piece and of the whole file, resume).
 *
 * <pre>
 * Presence (UDP broadcast to ports 2425-2429):
 *   "ESMSG1 ENTRY|ANSENTRY|EXIT id messagePort peerPort name group host"   (text fields URL-encoded)
 * Messages (TCP to messagePort):
 *   int MAGIC, byte MESSAGE, string msgId, fromId, fromName, fromGroup, fromHost, int msgPort, int peerPort,
 *       string text, int offerCount, offerCount x string metadata          -> byte OK
 *   int MAGIC, byte STATUS, string fromId, string infoHash, string status  -> byte OK
 * </pre>
 */
public final class MessengerService {
    public static final int BASE_UDP_PORT = 2425;
    public static final int UDP_PORTS = 5;
    private static final int MAGIC = 0x45534D31; // "ESM1"
    private static final byte TYPE_MESSAGE = 1;
    private static final byte TYPE_STATUS = 2;
    private static final byte TYPE_OK = 10;
    private static final long HEARTBEAT_SECONDS = 30;
    private static final long USER_TIMEOUT_MS = 100_000;

    public interface Listener {
        void usersChanged();

        void messageReceived(IncomingMessage message);

        void transfersChanged();

        void notice(String text);
    }

    private final MessengerConfig config;
    private final String id;
    private final Map<String, LanUser> users = new ConcurrentHashMap<>();
    private final List<Transfer> transfers = new CopyOnWriteArrayList<>();
    private final Set<String> manualHosts = ConcurrentHashMap.newKeySet();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<Path, CachedMeta> metaCache = new HashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(DaemonThreads.named("messenger"));
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(DaemonThreads.named("messenger-heartbeat"));
    private final String host = MessengerConfig.computerName();
    private PeerNode node;
    private DatagramSocket udp;
    private ServerSocket messageServer;
    private volatile boolean running;

    private record CachedMeta(long size, long modified, FileMeta meta) {
    }

    public MessengerService(MessengerConfig config) {
        this.config = config;
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        this.id = HashUtil.toHex(bytes);
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    // ---------------------------------------------------------------- lifecycle

    public void start() throws IOException {
        PeerConfig pc = new PeerConfig();
        pc.name = config.name;
        pc.port = NetUtil.freePort();
        pc.sharedDir = MessengerConfig.appDir().resolve("shared");
        pc.downloadsDir = config.receiveDir;
        pc.lanDiscovery = false;
        node = new PeerNode(pc);
        node.start();

        messageServer = new ServerSocket(0);
        udp = bindUdp();
        running = true;
        daemon(this::acceptMessages, "messenger-tcp");
        daemon(this::receivePresence, "messenger-udp");
        broadcast("ENTRY");
        scheduler.scheduleWithFixedDelay(() -> {
            broadcast("ENTRY");
            expireUsers();
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        Log.info("MESSENGER", config.name + " online: UDP " + udp.getLocalPort() + ", messages TCP " + messageServer.getLocalPort()
                + ", pieces TCP " + pc.port);
    }

    public void stop() {
        if (!running) {
            return;
        }
        broadcast("EXIT");
        running = false;
        scheduler.shutdownNow();
        udp.close();
        try {
            messageServer.close();
        } catch (IOException ignored) {
            // closing
        }
        node.stop();
        pool.shutdownNow();
    }

    private static DatagramSocket bindUdp() throws IOException {
        IOException last = null;
        for (int port = BASE_UDP_PORT; port < BASE_UDP_PORT + UDP_PORTS; port++) {
            try {
                DatagramSocket socket = new DatagramSocket(null);
                socket.setBroadcast(true);
                socket.bind(new InetSocketAddress(port));
                return socket;
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException("UDP ports " + BASE_UDP_PORT + "-" + (BASE_UDP_PORT + UDP_PORTS - 1) + " are all in use", last);
    }

    // ---------------------------------------------------------------- presence

    /** Sends our presence to every computer on the LAN (and to manually added IP addresses). */
    public void refresh() {
        broadcast("ENTRY");
    }

    public void addHost(String ip) throws IOException {
        InetAddress address = InetAddress.getByName(ip.trim());
        manualHosts.add(address.getHostAddress());
        broadcast("ENTRY");
    }

    /** Called after the user changed name/group in the settings. */
    public void settingsChanged() {
        node.config().name = config.name;
        node.config().downloadsDir = config.receiveDir;
        broadcast("ENTRY");
    }

    private void broadcast(String type) {
        byte[] data = presence(type);
        Set<InetAddress> targets = new LinkedHashSet<>();
        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
            targets.add(InetAddress.getLoopbackAddress());
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address && ia.getBroadcast() != null) {
                        targets.add(ia.getBroadcast());
                    }
                }
            }
            for (String h : manualHosts) {
                targets.add(InetAddress.getByName(h));
            }
        } catch (IOException ignored) {
            // send to what we have
        }
        for (InetAddress target : targets) {
            for (int port = BASE_UDP_PORT; port < BASE_UDP_PORT + UDP_PORTS; port++) {
                send(data, target, port);
            }
        }
    }

    private byte[] presence(String type) {
        return ("ESMSG1 " + type + " " + id + " " + messageServer.getLocalPort() + " " + node.config().port + " "
                + enc(config.name) + " " + enc(config.group) + " " + enc(host)).getBytes(StandardCharsets.UTF_8);
    }

    private void send(byte[] data, InetAddress address, int port) {
        try {
            udp.send(new DatagramPacket(data, data.length, address, port));
        } catch (IOException ignored) {
            // unreachable broadcast address etc.
        }
    }

    private void receivePresence() {
        byte[] buffer = new byte[2048];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                udp.receive(packet);
            } catch (IOException e) {
                continue;
            }
            String[] p = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).split(" ");
            if (p.length < 3 || !p[0].equals("ESMSG1") || p[2].equals(id)) {
                continue;
            }
            try {
                if (p[1].equals("EXIT")) {
                    LanUser gone = users.remove(p[2]);
                    if (gone != null) {
                        fireUsers();
                    }
                } else if ((p[1].equals("ENTRY") || p[1].equals("ANSENTRY")) && p.length >= 8) {
                    upsertUser(p[2], dec(p[5]), dec(p[6]), dec(p[7]), packet.getAddress().getHostAddress(),
                            Integer.parseInt(p[3]), Integer.parseInt(p[4]));
                    if (p[1].equals("ENTRY")) {
                        send(presence("ANSENTRY"), packet.getAddress(), packet.getPort());
                    }
                }
            } catch (RuntimeException ignored) {
                // malformed packet
            }
        }
    }

    private void upsertUser(String userId, String name, String group, String userHost, String ip, int messagePort, int peerPort) {
        boolean changed = false;
        LanUser user = users.get(userId);
        if (user == null) {
            user = new LanUser(userId);
            users.put(userId, user);
            changed = true;
        }
        changed |= !name.equals(user.name) || !group.equals(user.group) || !userHost.equals(user.host);
        user.name = name;
        user.group = group;
        user.host = userHost;
        user.messagePort = messagePort;
        user.peerPort = peerPort;
        user.lastSeen = System.currentTimeMillis();
        changed |= user.addAddress(ip);
        if (changed) {
            fireUsers();
        }
    }

    private void expireUsers() {
        long now = System.currentTimeMillis();
        if (users.values().removeIf(u -> now - u.lastSeen > USER_TIMEOUT_MS)) {
            fireUsers();
        }
    }

    public List<LanUser> users() {
        List<LanUser> list = new ArrayList<>(users.values());
        list.sort(Comparator.comparing((LanUser u) -> u.group.toLowerCase()).thenComparing(u -> u.name.toLowerCase()));
        return list;
    }

    // ---------------------------------------------------------------- sending

    /**
     * Sends a message with attached files/folders to the selected users (in the background).
     * Folders are packed into a .zip file first. Every file is split into pieces and hashed.
     */
    public void send(List<LanUser> recipients, String text, List<Path> attachments) {
        pool.submit(() -> {
            List<IncomingMessage.Offer> offers = new ArrayList<>();
            try {
                for (Path path : attachments) {
                    fireNotice("Preparing " + path.getFileName() + " (splitting into pieces and hashing)...");
                    Path file = Files.isDirectory(path) ? zipFolder(path) : path;
                    FileMeta meta = metaFor(file);
                    if (node.registry().get(meta.infoHash()) == null) {
                        node.registry().add(SharedFile.complete(meta, file));
                    }
                    offers.add(new IncomingMessage.Offer(meta.infoHash(), meta.name(), meta.length(), meta.text()));
                }
            } catch (IOException e) {
                fireNotice("Could not prepare attachments: " + e.getMessage());
                return;
            }
            String messageId = Long.toHexString(System.nanoTime());
            for (LanUser user : recipients) {
                List<Transfer> created = new ArrayList<>();
                for (IncomingMessage.Offer offer : offers) {
                    Transfer t = new Transfer(Transfer.Direction.SEND, user.id, user.name, offer.infoHash(), offer.name(),
                            offer.size(), null, "Waiting for " + user.name + " to accept");
                    t.uploadedAtStart = node.stats().uploaded(offer.infoHash());
                    created.add(t);
                }
                if (deliver(user, messageId, text, offers)) {
                    transfers.addAll(created);
                    fireNotice("Message delivered to " + user.label()
                            + (offers.isEmpty() ? "" : " with " + offers.size() + " file(s)"));
                } else {
                    fireNotice("Could not reach " + user.label() + ". Is it still online? Check the firewall.");
                }
            }
            fireTransfers();
        });
    }

    private boolean deliver(LanUser user, String messageId, String text, List<IncomingMessage.Offer> offers) {
        for (String address : user.addresses()) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, user.messagePort), 3000);
                socket.setSoTimeout(10_000);
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                out.writeInt(MAGIC);
                out.writeByte(TYPE_MESSAGE);
                Protocol.writeString(out, messageId);
                Protocol.writeString(out, id);
                Protocol.writeString(out, config.name);
                Protocol.writeString(out, config.group);
                Protocol.writeString(out, host);
                out.writeInt(messageServer.getLocalPort());
                out.writeInt(node.config().port);
                Protocol.writeString(out, text);
                out.writeInt(offers.size());
                for (IncomingMessage.Offer offer : offers) {
                    Protocol.writeString(out, offer.metaText());
                }
                out.flush();
                if (in.readByte() == TYPE_OK) {
                    return true;
                }
            } catch (IOException ignored) {
                // try the next address of this user
            }
        }
        return false;
    }

    private FileMeta metaFor(Path file) throws IOException {
        Path key = file.toAbsolutePath().normalize();
        long size = Files.size(key);
        long modified = Files.getLastModifiedTime(key).toMillis();
        synchronized (metaCache) {
            CachedMeta cached = metaCache.get(key);
            if (cached != null && cached.size() == size && cached.modified() == modified) {
                return cached.meta();
            }
        }
        FileMeta meta = FileMeta.create(key, 256 * 1024);
        synchronized (metaCache) {
            metaCache.put(key, new CachedMeta(size, modified, meta));
        }
        return meta;
    }

    private static Path zipFolder(Path folder) throws IOException {
        Path outbox = MessengerConfig.appDir().resolve("outbox");
        Files.createDirectories(outbox);
        String name = FileMeta.sanitizeName(folder.getFileName() == null ? "folder" : folder.getFileName().toString());
        Path zip = outbox.resolve((name == null ? "folder" : name) + ".zip");
        Path base = folder.toAbsolutePath().normalize();
        try (OutputStream fileOut = Files.newOutputStream(zip);
             ZipOutputStream zipOut = new ZipOutputStream(new BufferedOutputStream(fileOut));
             Stream<Path> walk = Files.walk(base)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String entry = base.getFileName() + "/" + base.relativize(p).toString().replace('\\', '/');
                zipOut.putNextEntry(new ZipEntry(entry));
                Files.copy(p, zipOut);
                zipOut.closeEntry();
            }
        }
        return zip;
    }

    // ---------------------------------------------------------------- receiving

    private void acceptMessages() {
        while (running) {
            try {
                Socket socket = messageServer.accept();
                pool.submit(() -> handleMessage(socket));
            } catch (IOException e) {
                if (!running) {
                    return;
                }
            }
        }
    }

    private void handleMessage(Socket socket) {
        try (socket) {
            socket.setSoTimeout(15_000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            if (in.readInt() != MAGIC) {
                return;
            }
            String ip = socket.getInetAddress().getHostAddress();
            byte type = in.readByte();
            if (type == TYPE_MESSAGE) {
                String messageId = Protocol.readString(in);
                String fromId = Protocol.readString(in);
                String fromName = Protocol.readString(in);
                String fromGroup = Protocol.readString(in);
                String fromHost = Protocol.readString(in);
                int messagePort = in.readInt();
                int peerPort = in.readInt();
                String text = Protocol.readString(in);
                int count = in.readInt();
                if (count < 0 || count > 10_000) {
                    return;
                }
                List<IncomingMessage.Offer> offers = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    FileMeta meta = FileMeta.parse(Protocol.readString(in));
                    offers.add(new IncomingMessage.Offer(meta.infoHash(), meta.name(), meta.length(), meta.text()));
                }
                out.writeByte(TYPE_OK);
                out.flush();
                upsertUser(fromId, fromName, fromGroup, fromHost, ip, messagePort, peerPort);
                IncomingMessage message = new IncomingMessage(messageId, fromId, fromName, fromGroup, fromHost, ip,
                        messagePort, peerPort, text, offers);
                Log.info("MESSENGER", "Message from " + fromName + " (" + ip + ")" + (offers.isEmpty() ? "" : " with " + offers.size() + " file(s)"));
                for (Listener l : listeners) {
                    l.messageReceived(message);
                }
            } else if (type == TYPE_STATUS) {
                String fromId = Protocol.readString(in);
                String infoHash = Protocol.readString(in);
                String status = Protocol.readString(in);
                out.writeByte(TYPE_OK);
                out.flush();
                for (Transfer t : transfers) {
                    if (t.direction == Transfer.Direction.SEND && t.userId.equals(fromId) && t.infoHash.equals(infoHash) && !t.finished) {
                        switch (status) {
                            case "ACCEPTED" -> t.status = "Sending...";
                            case "COMPLETED" -> {
                                t.status = "Delivered (SHA-1 verified by " + t.userName + ")";
                                t.finished = true;
                                t.ok = true;
                            }
                            case "DECLINED" -> {
                                t.status = "Declined by " + t.userName;
                                t.finished = true;
                            }
                            default -> {
                                t.status = "Not finished: " + status;
                                t.finished = true;
                            }
                        }
                    }
                }
                fireTransfers();
            }
        } catch (IOException ignored) {
            // bad or interrupted connection
        }
    }

    /** Downloads the chosen files of a message into the receive folder. */
    public void accept(IncomingMessage message, List<IncomingMessage.Offer> offers) {
        for (IncomingMessage.Offer offer : offers) {
            Transfer t = new Transfer(Transfer.Direction.RECEIVE, message.fromId, message.fromName, offer.infoHash(),
                    offer.name(), offer.size(), message, "Connecting to " + message.fromName + "...");
            transfers.add(t);
            pool.submit(() -> receive(t));
        }
        fireTransfers();
    }

    /** Tries an unfinished incoming transfer again (resumes from the pieces already verified). */
    public void retry(Transfer t) {
        if (t.canRetry()) {
            t.finished = false;
            t.status = "Retrying...";
            pool.submit(() -> receive(t));
            fireTransfers();
        }
    }

    public void decline(IncomingMessage message) {
        pool.submit(() -> {
            for (IncomingMessage.Offer offer : message.offers) {
                sendStatus(message, offer.infoHash(), "DECLINED");
            }
        });
    }

    private void receive(Transfer t) {
        IncomingMessage m = t.message;
        IncomingMessage.Offer offer = m.offers.stream().filter(o -> o.infoHash().equals(t.infoHash)).findFirst().orElseThrow();
        try {
            node.addKnownPeer(new PeerAddress(m.fromAddress, m.fromPeerPort));
            FileMeta meta = FileMeta.parse(offer.metaText());
            SharedFile existing = node.registry().get(meta.infoHash());
            if (existing != null && existing.isComplete()) {
                finish(t, true, "Already received: " + existing.path().getFileName(), existing.path());
                sendStatus(m, t.infoHash, "COMPLETED");
                return;
            }
            node.config().downloadsDir = config.receiveDir;
            Download d = node.startDownload(meta);
            t.download = d;
            t.status = "Receiving...";
            fireTransfers();
            sendStatus(m, t.infoHash, "ACCEPTED");
            while (d.state() == Download.State.RUNNING) {
                Thread.sleep(300);
            }
            if (d.state() == Download.State.COMPLETED) {
                finish(t, true, "Received (" + d.hashFailures() + " bad pieces re-downloaded, SHA-1 verified)", d.result());
                sendStatus(m, t.infoHash, "COMPLETED");
            } else {
                finish(t, false, d.message(), null);
                sendStatus(m, t.infoHash, "FAILED");
            }
        } catch (IOException e) {
            finish(t, false, "Failed: " + e.getMessage(), null);
            sendStatus(m, t.infoHash, "FAILED");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void finish(Transfer t, boolean ok, String status, Path result) {
        t.ok = ok;
        t.status = status;
        t.result = result;
        t.finished = true;
        fireTransfers();
        if (ok) {
            fireNotice("Received " + t.fileName + " from " + t.userName);
        }
    }

    private void sendStatus(IncomingMessage m, String infoHash, String status) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(m.fromAddress, m.fromMessagePort), 3000);
            socket.setSoTimeout(5000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            out.writeInt(MAGIC);
            out.writeByte(TYPE_STATUS);
            Protocol.writeString(out, id);
            Protocol.writeString(out, infoHash);
            Protocol.writeString(out, status);
            out.flush();
            socket.getInputStream().read();
        } catch (IOException ignored) {
            // sender went offline; the status is only informational
        }
    }

    // ---------------------------------------------------------------- state for the GUI

    public List<Transfer> transfers() {
        return new ArrayList<>(transfers);
    }

    public void clearFinished() {
        transfers.removeIf(Transfer::finished);
        fireTransfers();
    }

    public int progress(Transfer t) {
        if (t.finished && t.ok) {
            return 100;
        }
        if (t.direction == Transfer.Direction.RECEIVE) {
            Download d = t.download;
            return d == null ? 0 : d.percent();
        }
        long sent = node.stats().uploaded(t.infoHash) - t.uploadedAtStart;
        return (int) Math.max(0, Math.min(99, sent * 100 / Math.max(1, t.size)));
    }

    public MessengerConfig config() {
        return config;
    }

    public int udpPort() {
        return udp.getLocalPort();
    }

    // ---------------------------------------------------------------- helpers

    private void fireUsers() {
        listeners.forEach(Listener::usersChanged);
    }

    private void fireTransfers() {
        listeners.forEach(Listener::transfersChanged);
    }

    private void fireNotice(String text) {
        Log.info("MESSENGER", text);
        listeners.forEach(l -> l.notice(text));
    }

    private static void daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
