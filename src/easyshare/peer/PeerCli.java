package easyshare.peer;

import easyshare.common.Format;
import easyshare.common.HashUtil;
import easyshare.common.NetUtil;
import easyshare.common.PeerAddress;
import easyshare.meta.FileMeta;
import easyshare.tracker.TrackerClient;
import easyshare.web.PhonePage;
import easyshare.web.WebDashboard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.BindException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Interactive console for a peer. */
public final class PeerCli {
    private final PeerNode node;
    private final BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
    private List<PeerNode.SearchResult> lastResults = List.of();

    private PeerCli(PeerNode node) {
        this.node = node;
    }

    public static void run(PeerConfig config) throws IOException {
        PeerNode node = new PeerNode(config);
        printBanner(config);
        try {
            node.start();
        } catch (BindException e) {
            System.out.println("ERROR: port " + config.port + " is already in use. Start this peer with another --port.");
            node.stop();
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(node::stop));
        if (config.webPort > 0) {
            try {
                WebDashboard web = new WebDashboard(node, config.webPort);
                web.start();
                System.out.println("  Web dashboard : http://localhost:" + config.webPort + "/");
            } catch (IOException e) {
                System.out.println("  Web dashboard could not start on port " + config.webPort + ": " + e.getMessage());
            }
        }
        if (config.phonePort > 0) {
            try {
                new PhonePage(node, config.phonePort).start();
                System.out.println("  Phone page    : on your phone (same Wi-Fi) open the address of your Wi-Fi adapter:");
                for (String entry : NetUtil.lanAddressesWithAdapter()) {
                    int space = entry.indexOf(' ');
                    System.out.println("                  http://" + entry.substring(0, space) + ":" + config.phonePort + "/" + entry.substring(space));
                }
            } catch (IOException e) {
                System.out.println("  Phone page could not start on port " + config.phonePort + ": " + e.getMessage());
            }
        }
        PeerCli cli = new PeerCli(node);
        System.out.println();
        cli.myFiles();
        System.out.println("\nType 'help' to see all commands.\n");
        cli.loop();
        node.stop();
        System.exit(0);
    }

    private static void printBanner(PeerConfig c) {
        System.out.println("==============================================================");
        System.out.println("  EasyShare - Peer-to-Peer File Sharing (BitTorrent-style)");
        System.out.println("==============================================================");
        System.out.println("  Peer name     : " + c.name);
        System.out.println("  Listening on  : TCP port " + c.port + "   (this PC: " + String.join(", ", NetUtil.lanAddresses()) + ")");
        System.out.println("  Shared folder : " + c.sharedDir.toAbsolutePath().normalize());
        System.out.println("  Downloads     : " + c.downloadsDir.toAbsolutePath().normalize());
        System.out.println("  Tracker       : " + (c.trackerHost == null ? "none (use 'discover' or 'connect')" : c.trackerHost + ":" + c.trackerPort));
        System.out.println("  Piece size    : " + c.pieceKb + " KB     Upload limit: "
                + (c.uploadLimitKbps == 0 ? "unlimited" : c.uploadLimitKbps + " KB/s"));
        if (c.corruptPercent > 0) {
            System.out.println("  SIMULATION    : " + c.corruptPercent + "% of uploaded pieces will be corrupted on purpose");
        }
        System.out.println("==============================================================");
    }

    private void loop() throws IOException {
        while (true) {
            System.out.print(node.config().name + "> ");
            System.out.flush();
            String line = console.readLine();
            if (line == null) {
                return;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+", 2);
            String command = parts[0].toLowerCase();
            String arg = parts.length > 1 ? parts[1].trim() : "";
            try {
                switch (command) {
                    case "help", "?" -> help();
                    case "myfiles", "files", "ls" -> myFiles();
                    case "info" -> info(arg);
                    case "verify" -> verify(arg);
                    case "refresh" -> {
                        int n = node.refresh();
                        System.out.println("Shared folder rescanned: " + n + " file(s) shared.");
                        myFiles();
                    }
                    case "export" -> export(arg);
                    case "peers" -> peers();
                    case "discover" -> discover();
                    case "connect" -> connect(arg);
                    case "search", "find" -> search(arg);
                    case "get", "download" -> get(arg);
                    case "open" -> open(arg);
                    case "resume" -> resume(arg);
                    case "downloads", "status" -> downloads();
                    case "stats" -> stats();
                    case "quit", "exit" -> {
                        System.out.println("Leaving the network...");
                        return;
                    }
                    default -> System.out.println("Unknown command '" + command + "'. Type 'help'.");
                }
            } catch (IllegalArgumentException | IOException e) {
                System.out.println("ERROR: " + e.getMessage());
            }
        }
    }

    private void help() {
        System.out.println("""
                MY FILES
                  myfiles               List files you share (and unfinished downloads)
                  info <#>              Show pieces (chunks) and SHA-1 hashes of your file #
                  verify <#>            Re-hash every piece of your file # to check integrity
                  refresh               Rescan the shared folder after adding files
                  export <#>            Save a .p2pmeta (torrent-like) file for your file #
                NETWORK
                  peers                 List online peers (tracker + manual + LAN)
                  discover              Find peers on the local network without a tracker
                  connect <ip:port>     Add a peer manually and list its files
                  search [keyword]      Search the network for files (empty = list all)
                DOWNLOAD
                  get <#>               Download result # of the last search/connect
                  get <info hash>       Download a file by its 40-character info hash
                  open <file.p2pmeta>   Download using a .p2pmeta file
                  resume [#]            Continue an unfinished download
                  downloads             Show downloads and their progress
                  stats                 Total uploaded / downloaded bytes
                OTHER
                  help                  Show this help
                  quit                  Leave the network and exit
                While a download runs, press ENTER to pause it.""");
    }

    // ---------------------------------------------------------------- my files

    private void myFiles() {
        List<SharedFile> files = node.registry().list();
        if (files.isEmpty()) {
            System.out.println("You are not sharing any files. Copy files into "
                    + node.config().sharedDir.toAbsolutePath().normalize() + " and type 'refresh'.");
            return;
        }
        System.out.println("My files:");
        System.out.println("  #   Name                           Size        Pieces   Status        Info hash");
        int i = 1;
        for (SharedFile f : files) {
            String status;
            if (f.isComplete()) {
                status = "seeding";
            } else {
                Download d = node.download(f.meta().infoHash());
                status = f.percent() + "% " + (d != null && d.state() == Download.State.RUNNING ? "downloading" : "paused");
            }
            System.out.println("  " + Format.fit(String.valueOf(i++), 3) + " " + Format.fit(f.meta().name(), 30) + " "
                    + Format.fit(Format.bytes(f.meta().length()), 11) + " " + Format.fit(String.valueOf(f.meta().pieceCount()), 8)
                    + " " + Format.fit(status, 13) + " " + Format.shortHash(f.meta().infoHash()));
        }
    }

    private SharedFile myFile(String arg) {
        List<SharedFile> files = node.registry().list();
        int index = number(arg, files.size(), "myfiles");
        return files.get(index);
    }

    private void info(String arg) {
        SharedFile f = myFile(arg);
        FileMeta m = f.meta();
        System.out.println("Name          : " + m.name());
        System.out.println("Location      : " + f.path().toAbsolutePath().normalize());
        System.out.println("Size          : " + Format.bytes(m.length()) + " (" + m.length() + " bytes)");
        System.out.println("Piece size    : " + Format.bytes(m.pieceLength()) + "   Pieces: " + m.pieceCount());
        System.out.println("Info hash     : " + m.infoHash() + "   (SHA-1 of the metadata = file ID)");
        System.out.println("File SHA-1    : " + m.fileHash());
        System.out.println("Have pieces   : " + f.haveCount() + "/" + m.pieceCount() + "   (# = have, . = missing)");
        System.out.println(Format.pieceMap(f.bitfield(), m.pieceCount(), 60).indent(2).stripTrailing());
        System.out.println("Piece hashes (SHA-1):");
        int show = Math.min(5, m.pieceCount());
        for (int i = 0; i < show; i++) {
            System.out.println("  piece " + Format.fit(String.valueOf(i), 5) + " offset " + Format.fit(String.valueOf(m.pieceOffset(i)), 11)
                    + " " + Format.fit(m.pieceSize(i) + " B", 10) + " " + m.pieceHash(i));
        }
        if (m.pieceCount() > show) {
            System.out.println("  ... " + (m.pieceCount() - show) + " more");
        }
    }

    private void verify(String arg) throws IOException {
        SharedFile f = myFile(arg);
        FileMeta m = f.meta();
        System.out.println("Verifying " + m.name() + " piece by piece...");
        int ok = 0;
        int bad = 0;
        int missing = 0;
        for (int i = 0; i < m.pieceCount(); i++) {
            byte[] data = f.readPiece(i);
            if (data == null) {
                missing++;
            } else if (HashUtil.sha1Hex(data).equals(m.pieceHash(i))) {
                ok++;
            } else {
                bad++;
                System.out.println("  piece #" + i + " is CORRUPTED (hash mismatch)");
            }
        }
        System.out.println("  Pieces OK: " + ok + "   corrupted: " + bad + "   not downloaded yet: " + missing);
        if (f.isComplete()) {
            String hash = HashUtil.sha1File(f.path());
            boolean match = hash.equals(m.fileHash());
            System.out.println("  Whole-file SHA-1: " + hash);
            System.out.println("  Expected SHA-1  : " + m.fileHash());
            System.out.println("  Integrity       : " + (match ? "VERIFIED" : "FAILED - file was modified or damaged"));
        }
    }

    private void export(String arg) throws IOException {
        SharedFile f = myFile(arg);
        Path out = Path.of(f.meta().name() + FileMeta.EXTENSION).toAbsolutePath().normalize();
        f.meta().save(out);
        System.out.println("Metadata saved to " + out);
        System.out.println("Give this file to another user; they type:  open \"" + out.getFileName() + "\"");
    }

    // ---------------------------------------------------------------- network

    private void peers() {
        if (node.hasTracker()) {
            try {
                List<TrackerClient.PeerInfo> list = node.trackerPeers();
                System.out.println("Peers registered at tracker " + node.trackerAddress() + ": " + list.size());
                for (TrackerClient.PeerInfo p : list) {
                    String you = node.isSelf(p.address()) ? "  <- you" : "";
                    System.out.println("  " + Format.fit(p.name(), 16) + " " + Format.fit(p.address().toString(), 22) + " "
                            + p.value() + " file(s)" + you);
                }
            } catch (IOException e) {
                System.out.println("Tracker " + node.trackerAddress() + " not reachable: " + e.getMessage());
            }
        } else {
            System.out.println("No tracker configured.");
        }
        if (!node.knownPeers().isEmpty()) {
            System.out.println("Peers added with connect/discover:");
            for (PeerAddress a : node.knownPeers()) {
                System.out.println("  " + a);
            }
        }
    }

    private void discover() throws IOException {
        System.out.println("Sending LAN discovery broadcast (UDP multicast, port " + LanDiscovery.PORT + ")...");
        List<LanDiscovery.Found> found = node.discoverLan();
        if (found.isEmpty()) {
            System.out.println("No peers answered. (Check that other peers are running and the firewall allows Java.)");
            return;
        }
        System.out.println("Found " + found.size() + " peer(s):");
        for (LanDiscovery.Found f : found) {
            System.out.println("  " + Format.fit(f.name(), 16) + " " + f.address());
        }
        System.out.println("They were added to your peer list. Type 'search' to see their files.");
    }

    private void connect(String arg) throws IOException {
        if (arg.isEmpty()) {
            throw new IllegalArgumentException("Usage: connect <ip:port>   e.g. connect 192.168.1.10:6001");
        }
        PeerAddress address = PeerAddress.parse(arg, -1);
        try (PeerClient client = node.connect(address)) {
            List<PeerClient.RemoteFile> files = client.listFiles();
            node.addKnownPeer(address);
            System.out.println("Connected to " + client.remoteName() + " at " + address + ". Files available:");
            lastResults = files.stream()
                    .map(f -> new PeerNode.SearchResult(f.infoHash(), f.name(), f.length(), 1,
                            f.haveCount() == f.pieceCount() ? 1 : 0, node.registry().get(f.infoHash()) != null))
                    .toList();
            printResults();
        }
    }

    private void search(String keyword) {
        lastResults = node.search(keyword);
        if (lastResults.isEmpty()) {
            System.out.println("No files found" + (keyword.isEmpty() ? "." : " matching '" + keyword + "'."));
            if (!node.hasTracker() && node.knownPeers().isEmpty()) {
                System.out.println("You have no tracker and no known peers. Use 'discover' or 'connect <ip:port>' first.");
            }
            return;
        }
        System.out.println("Search results:");
        printResults();
    }

    private void printResults() {
        System.out.println("  #   Name                           Size        Peers  Seeders  Info hash");
        int i = 1;
        for (PeerNode.SearchResult r : lastResults) {
            System.out.println("  " + Format.fit(String.valueOf(i++), 3) + " " + Format.fit(r.name(), 30) + " "
                    + Format.fit(Format.bytes(r.length()), 11) + " " + Format.fit(String.valueOf(r.peers()), 6) + " "
                    + Format.fit(String.valueOf(r.seeders()), 8) + " " + Format.shortHash(r.infoHash())
                    + (r.local() ? "  (you have it)" : ""));
        }
        System.out.println("Type 'get <#>' to download.");
    }

    // ---------------------------------------------------------------- download

    private void get(String arg) throws IOException {
        if (arg.isEmpty()) {
            throw new IllegalArgumentException("Usage: get <#>  (number from search)  or  get <info hash>");
        }
        String infoHash;
        if (HashUtil.isSha1Hex(arg.toLowerCase())) {
            infoHash = arg.toLowerCase();
        } else {
            infoHash = lastResults.get(number(arg, lastResults.size(), "search")).infoHash();
        }
        System.out.println("Requesting metadata (piece list) from peers...");
        FileMeta meta = node.fetchMeta(infoHash);
        startAndWatch(meta);
    }

    private void open(String arg) throws IOException {
        if (arg.isEmpty()) {
            throw new IllegalArgumentException("Usage: open <file.p2pmeta>");
        }
        Path file = Path.of(arg.replace("\"", ""));
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("File not found: " + file.toAbsolutePath());
        }
        startAndWatch(FileMeta.load(file));
    }

    private void resume(String arg) throws IOException {
        List<SharedFile> unfinished = node.unfinished();
        if (unfinished.isEmpty()) {
            System.out.println("There are no unfinished downloads.");
            return;
        }
        SharedFile f = arg.isEmpty() ? unfinished.get(0) : unfinished.get(number(arg, unfinished.size(), "downloads"));
        System.out.println("Resuming " + f.meta().name() + " from " + f.haveCount() + "/" + f.meta().pieceCount() + " pieces.");
        startAndWatch(f.meta());
    }

    private void startAndWatch(FileMeta meta) throws IOException {
        Download d = node.startDownload(meta);
        System.out.println("Downloading " + meta.name() + " (" + Format.bytes(meta.length()) + ", " + meta.pieceCount()
                + " pieces of " + Format.bytes(meta.pieceLength()) + ")");
        if (d.initialPieces() > 0) {
            System.out.println("Resuming: " + d.initialPieces() + " pieces were already downloaded and verified.");
        }
        System.out.println("Press ENTER to pause.\n");
        watch(d);
    }

    private void watch(Download d) throws IOException {
        long lastBytes = d.bytesReceived();
        long lastTime = System.nanoTime();
        double speed = 0;
        while (true) {
            String event;
            while ((event = d.pollEvent()) != null) {
                clearLine();
                System.out.println("  ! " + event);
            }
            long now = System.nanoTime();
            if (now - lastTime >= 1_000_000_000L) {
                long bytes = d.bytesReceived();
                speed = (bytes - lastBytes) * 1e9 / (now - lastTime);
                lastBytes = bytes;
                lastTime = now;
            }
            int pct = d.percent();
            System.out.print("\r  [" + Format.bar(pct, 30) + "] " + Format.fit(pct + "%", 4) + " "
                    + d.doneCount() + "/" + d.meta().pieceCount() + " pieces  " + Format.fit(Format.bytes((long) speed) + "/s", 11)
                    + " peers: " + d.activePeers() + "  bad pieces: " + d.hashFailures() + "   ");
            System.out.flush();

            if (d.state() != Download.State.RUNNING) {
                break;
            }
            if (System.in.available() > 0) {
                console.readLine();
                d.pause();
                break;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        String event;
        while ((event = d.pollEvent()) != null) {
            clearLine();
            System.out.println("  ! " + event);
        }
        System.out.println();
        printSummary(d);
    }

    private void printSummary(Download d) {
        System.out.println();
        switch (d.state()) {
            case COMPLETED -> {
                double seconds = Math.max(0.001, d.elapsedMs() / 1000.0);
                System.out.println("DOWNLOAD COMPLETE: " + d.result().toAbsolutePath().normalize());
                System.out.printf("  Time: %.1f s   Average speed: %s/s%n", seconds, Format.bytes((long) (d.bytesReceived() / seconds)));
                System.out.println("  Pieces received from each peer:");
                for (Map.Entry<String, Integer> e : d.piecesPerPeer().entrySet()) {
                    System.out.println("    " + Format.fit(e.getKey(), 36) + " " + e.getValue() + " pieces");
                }
                if (d.initialPieces() > 0) {
                    System.out.println("    " + Format.fit("(already on disk - resumed)", 36) + " " + d.initialPieces() + " pieces");
                }
                System.out.println("  Corrupted pieces detected and re-downloaded: " + d.hashFailures());
                System.out.println("  Every piece passed its SHA-1 check.");
                System.out.println("  Whole-file SHA-1: " + d.meta().fileHash() + "  -> Integrity: VERIFIED");
                System.out.println("  You are now seeding this file to other peers.");
            }
            case PAUSED -> System.out.println("PAUSED: " + d.message() + "\n  Verified pieces are kept on disk - progress is not lost.");
            case FAILED -> System.out.println("FAILED: " + d.message());
            default -> {
            }
        }
    }

    private void downloads() {
        List<Download> list = node.downloads();
        List<SharedFile> unfinished = node.unfinished();
        if (list.isEmpty() && unfinished.isEmpty()) {
            System.out.println("No downloads in this session.");
            return;
        }
        if (!unfinished.isEmpty()) {
            System.out.println("Unfinished downloads (use 'resume <#>'):");
            int i = 1;
            for (SharedFile f : unfinished) {
                System.out.println("  " + Format.fit(String.valueOf(i++), 3) + " " + Format.fit(f.meta().name(), 30) + " ["
                        + Format.bar(f.percent(), 20) + "] " + f.percent() + "%  " + f.haveCount() + "/" + f.meta().pieceCount() + " pieces");
            }
        }
        List<Download> finished = list.stream().filter(d -> d.state() == Download.State.COMPLETED).toList();
        if (!finished.isEmpty()) {
            System.out.println("Completed in this session:");
            for (Download d : finished) {
                System.out.println("  " + Format.fit(d.meta().name(), 30) + " " + Format.bytes(d.meta().length()) + " -> " + d.result());
            }
        }
    }

    private void stats() {
        System.out.println("Uploaded   : " + Format.bytes(node.stats().uploaded()));
        System.out.println("Downloaded : " + Format.bytes(node.stats().downloaded()));
        System.out.println("Upload connections open now: " + node.uploadConnections());
    }

    // ---------------------------------------------------------------- helpers

    private static int number(String arg, int size, String listCommand) {
        if (size == 0) {
            throw new IllegalArgumentException("The list is empty. Run '" + listCommand + "' first.");
        }
        try {
            int n = Integer.parseInt(arg.trim());
            if (n < 1 || n > size) {
                throw new IllegalArgumentException("Choose a number between 1 and " + size + " (see '" + listCommand + "').");
            }
            return n - 1;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected a number from '" + listCommand + "', got '" + arg + "'.");
        }
    }

    private static void clearLine() {
        System.out.print("\r" + " ".repeat(110) + "\r");
    }
}
