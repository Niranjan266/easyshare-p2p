package easyshare.tracker;

import easyshare.common.DaemonThreads;
import easyshare.common.Format;
import easyshare.common.HashUtil;
import easyshare.common.Log;
import easyshare.common.NetUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tracker = peer discovery service (like a BitTorrent tracker).
 * It never stores or transfers file data. It only remembers which peer (IP:port) has which file (info hash).
 * Peers send a heartbeat every 15 seconds; peers silent for 60 seconds are removed.
 *
 * <p>Text protocol, one command per line (names are URL-encoded):
 * <pre>
 *   REGISTER   name host port                              -> OK
 *   ANNOUNCE   host port infoHash length percent name      -> OK
 *   UNREGISTER host port                                   -> OK
 *   PEERS                                                  -> PEER name host port fileCount ... END
 *   GETPEERS   infoHash                                    -> PEER name host port percent ... END
 *   SEARCH     keyword                                     -> FILE infoHash length holders seeders name ... END
 *   QUIT
 * </pre>
 * host "-" means "use the IP address this connection comes from".
 */
public final class TrackerServer {
    public static final long PEER_TIMEOUT_MS = 60_000;

    private final int port;
    private final ExecutorService pool = Executors.newCachedThreadPool(DaemonThreads.named("tracker-client"));
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(DaemonThreads.named("tracker-cleaner"));
    private final Map<String, PeerEntry> peers = new ConcurrentHashMap<>();
    private final Map<String, FileEntry> files = new ConcurrentHashMap<>();
    private ServerSocket serverSocket;
    private volatile boolean running;

    public TrackerServer(int port) {
        this.port = port;
    }

    public static final class PeerEntry {
        public final String name;
        public final String host;
        public final int port;
        volatile long lastSeen = System.currentTimeMillis();
        /** info hash -> last announced percent, only used to avoid logging every heartbeat */
        final Map<String, Integer> logged = new ConcurrentHashMap<>();

        PeerEntry(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }
    }

    public static final class FileEntry {
        public final String infoHash;
        public volatile String name = "";
        public volatile long length;
        /** peer key (host:port) -> percent downloaded (100 = seeder) */
        public final Map<String, Integer> holders = new ConcurrentHashMap<>();

        FileEntry(String infoHash) {
            this.infoHash = infoHash;
        }
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(port));
        running = true;
        Thread acceptor = new Thread(this::acceptLoop, "tracker-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        cleaner.scheduleWithFixedDelay(this::removeExpiredPeers, 5, 5, TimeUnit.SECONDS);
    }

    public void stop() {
        running = false;
        cleaner.shutdownNow();
        pool.shutdownNow();
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // closing
        }
    }

    public int port() {
        return port;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                pool.submit(() -> handle(socket));
            } catch (IOException e) {
                if (running) {
                    Log.info("TRACKER", "Accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void handle(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)))) {
            socket.setSoTimeout(30_000);
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.equals("QUIT")) {
                    break;
                }
                try {
                    process(line.split(" "), socket, out);
                } catch (RuntimeException e) {
                    out.println("ERROR " + e.getMessage());
                }
                out.flush();
            }
        } catch (IOException ignored) {
            // client disconnected
        }
    }

    private void process(String[] p, Socket socket, PrintWriter out) {
        switch (p[0]) {
            case "REGISTER" -> {
                need(p, 4);
                String host = hostOf(p[2], socket);
                int peerPort = parsePort(p[3]);
                registerPeer(decode(p[1]), host, peerPort);
                out.println("OK");
            }
            case "ANNOUNCE" -> {
                need(p, 7);
                String host = hostOf(p[1], socket);
                int peerPort = parsePort(p[2]);
                String hash = p[3];
                if (!HashUtil.isSha1Hex(hash)) {
                    throw new IllegalArgumentException("Bad info hash");
                }
                String key = host + ":" + peerPort;
                PeerEntry peer = peers.get(key);
                if (peer == null) {
                    peer = registerPeer("unknown", host, peerPort);
                }
                FileEntry file = files.computeIfAbsent(hash, FileEntry::new);
                file.name = decode(p[6]);
                file.length = Long.parseLong(p[4]);
                int percent = Integer.parseInt(p[5]);
                file.holders.put(key, percent);
                Integer previous = peer.logged.put(hash, percent);
                if (previous == null) {
                    Log.info("TRACKER", peer.name + " announced " + file.name + " (" + Format.bytes(file.length)
                            + ", " + percent + "%)  info hash " + Format.shortHash(hash));
                } else if (percent == 100 && previous < 100) {
                    Log.info("TRACKER", peer.name + " finished " + file.name + " and is now a seeder");
                }
                out.println("OK");
            }
            case "UNREGISTER" -> {
                need(p, 3);
                removePeer(hostOf(p[1], socket) + ":" + parsePort(p[2]), "left the network");
                out.println("OK");
            }
            case "PEERS" -> {
                for (PeerEntry peer : peers.values()) {
                    String key = peer.host + ":" + peer.port;
                    long count = files.values().stream().filter(f -> f.holders.containsKey(key)).count();
                    out.println("PEER " + encode(peer.name) + " " + advertise(peer.host, socket) + " " + peer.port + " " + count);
                }
                out.println("END");
            }
            case "GETPEERS" -> {
                need(p, 2);
                FileEntry file = files.get(p[1]);
                if (file != null) {
                    for (Map.Entry<String, Integer> holder : file.holders.entrySet()) {
                        PeerEntry peer = peers.get(holder.getKey());
                        if (peer != null) {
                            out.println("PEER " + encode(peer.name) + " " + advertise(peer.host, socket) + " "
                                    + peer.port + " " + holder.getValue());
                        }
                    }
                }
                out.println("END");
            }
            case "SEARCH" -> {
                String keyword = p.length > 1 ? decode(p[1]).toLowerCase() : "";
                for (FileEntry file : files.values()) {
                    if (file.holders.isEmpty()) {
                        continue;
                    }
                    if (keyword.isEmpty() || keyword.equals("*") || file.name.toLowerCase().contains(keyword)) {
                        long seeders = file.holders.values().stream().filter(v -> v == 100).count();
                        out.println("FILE " + file.infoHash + " " + file.length + " " + file.holders.size() + " "
                                + seeders + " " + encode(file.name));
                    }
                }
                out.println("END");
            }
            default -> out.println("ERROR Unknown command " + p[0]);
        }
    }

    private PeerEntry registerPeer(String name, String host, int peerPort) {
        String key = host + ":" + peerPort;
        PeerEntry existing = peers.get(key);
        if (existing != null && existing.name.equals(name)) {
            existing.lastSeen = System.currentTimeMillis();
            // the peer re-sends all its files after REGISTER, so forget the old list first
            files.values().forEach(f -> f.holders.remove(key));
            return existing;
        }
        PeerEntry entry = new PeerEntry(name, host, peerPort);
        peers.put(key, entry);
        files.values().forEach(f -> f.holders.remove(key));
        Log.info("TRACKER", "Peer joined: " + name + " at " + key + "   (online peers: " + peers.size() + ")");
        return entry;
    }

    private void removePeer(String key, String reason) {
        PeerEntry removed = peers.remove(key);
        files.values().forEach(f -> f.holders.remove(key));
        files.values().removeIf(f -> f.holders.isEmpty());
        if (removed != null) {
            Log.info("TRACKER", "Peer " + reason + ": " + removed.name + " at " + key + "   (online peers: " + peers.size() + ")");
        }
    }

    private void removeExpiredPeers() {
        long now = System.currentTimeMillis();
        for (PeerEntry peer : new ArrayList<>(peers.values())) {
            if (now - peer.lastSeen > PEER_TIMEOUT_MS) {
                removePeer(peer.host + ":" + peer.port, "timed out");
            }
        }
    }

    /** A peer registered as 127.0.0.1 is reachable by remote computers through the tracker's own LAN address. */
    private static String advertise(String host, Socket requester) {
        if (NetUtil.isLoopback(host) && !requester.getInetAddress().isLoopbackAddress()) {
            return requester.getLocalAddress().getHostAddress();
        }
        return host;
    }

    private static String hostOf(String given, Socket socket) {
        return given.equals("-") ? socket.getInetAddress().getHostAddress() : given;
    }

    private static int parsePort(String s) {
        int value = Integer.parseInt(s);
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException("Bad port");
        }
        return value;
    }

    private static void need(String[] parts, int count) {
        if (parts.length < count) {
            throw new IllegalArgumentException("Missing arguments for " + parts[0]);
        }
    }

    static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    public List<PeerEntry> peerList() {
        List<PeerEntry> list = new ArrayList<>(peers.values());
        list.sort(Comparator.comparing(e -> e.name));
        return list;
    }

    public List<FileEntry> fileList() {
        List<FileEntry> list = new ArrayList<>(files.values());
        list.sort(Comparator.comparing(e -> e.name));
        return list;
    }
}
