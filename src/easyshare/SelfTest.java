package easyshare;

import easyshare.common.HashUtil;
import easyshare.common.Log;
import easyshare.common.NetUtil;
import easyshare.meta.FileMeta;
import easyshare.peer.Download;
import easyshare.peer.PeerConfig;
import easyshare.peer.PeerNode;
import easyshare.peer.SharedFile;
import easyshare.tracker.TrackerServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Automated end-to-end test: starts a tracker and several peers inside one JVM on localhost
 * and checks chunked multi-source download, SHA-1 corruption detection, re-seeding and resume.
 */
final class SelfTest {
    private static int passed;
    private static int failed;

    private SelfTest() {
    }

    interface Check {
        boolean run() throws Exception;
    }

    static boolean run() throws Exception {
        Log.setQuiet(true);
        Path root = Files.createTempDirectory("easyshare-selftest");
        List<PeerNode> nodes = new ArrayList<>();
        TrackerServer tracker = new TrackerServer(NetUtil.freePort());
        tracker.start();
        System.out.println("EasyShare self-test (temporary folder " + root + ")\n");

        try {
            // ---- metadata / hashing
            Path sample = root.resolve("sample.bin");
            writeRandom(sample, 1024 * 1024 + 123, 1);
            FileMeta meta = FileMeta.create(sample, 64 * 1024);
            check("File is split into pieces (1 MB / 64 KB = 17 pieces)", () -> meta.pieceCount() == 17);
            check("Metadata survives serialize -> parse with the same info hash",
                    () -> FileMeta.parse(meta.text()).infoHash().equals(meta.infoHash()));
            check("Changing one piece hash changes the info hash (tamper-evident)", () -> {
                String h = meta.pieceHash(3);
                String flipped = (h.charAt(0) == 'a' ? 'b' : 'a') + h.substring(1);
                return !FileMeta.parse(meta.text().replace(h, flipped)).infoHash().equals(meta.infoHash());
            });
            check("Path traversal file names in metadata are rejected", () -> {
                try {
                    FileMeta.parse(meta.text().replace("name=sample.bin", "name=../../evil.bin"));
                    return false;
                } catch (IOException e) {
                    return true;
                }
            });

            // ---- swarm: Alice and Bob seed the same file, Bob corrupts 30% of pieces
            Path movie = root.resolve("Alice/shared/movie.bin");
            writeRandom(movie, 3 * 1024 * 1024 + 777, 2);
            Files.createDirectories(root.resolve("Bob/shared"));
            Files.copy(movie, root.resolve("Bob/shared/movie.bin"));
            String movieHash = HashUtil.sha1File(movie);

            PeerNode alice = start(nodes, root, "Alice", tracker, 0, 0);
            PeerNode bob = start(nodes, root, "Bob", tracker, 0, 30);
            PeerNode carol = start(nodes, root, "Carol", tracker, 0, 0);

            check("Tracker search finds movie.bin shared by 2 peers", () -> carol.search("movie").stream()
                    .anyMatch(r -> r.name().equals("movie.bin") && r.peers() == 2 && r.seeders() == 2));

            String infoHash = carol.search("movie").get(0).infoHash();
            Download d1 = carol.startDownload(carol.fetchMeta(infoHash));
            waitFor(d1, 60);
            check("Carol downloads movie.bin (3 MB, 49 pieces) from the swarm", () -> d1.state() == Download.State.COMPLETED);
            check("Downloaded file SHA-1 equals the original", () -> HashUtil.sha1File(d1.result()).equals(movieHash));
            check("Pieces were received from both Alice and Bob in parallel", () -> d1.piecesPerPeer().size() == 2);
            check("Corrupted pieces from Bob were detected by SHA-1 and re-downloaded", () -> d1.hashFailures() > 0);
            System.out.println("      pieces per peer: " + d1.piecesPerPeer() + ", corrupted pieces rejected: " + d1.hashFailures());

            // ---- re-seeding: only Carol has the file now
            alice.stop();
            bob.stop();
            PeerNode dave = start(nodes, root, "Dave", tracker, 0, 0);
            Download d2 = dave.startDownload(dave.fetchMeta(infoHash));
            waitFor(d2, 60);
            check("After Alice and Bob leave, Dave downloads the file from Carol (a former downloader)",
                    () -> d2.state() == Download.State.COMPLETED && HashUtil.sha1File(d2.result()).equals(movieHash));

            // ---- resume after the downloader restarts
            Path big = root.resolve("Erin/shared/lecture.bin");
            writeRandom(big, 2 * 1024 * 1024, 3);
            String bigHash = HashUtil.sha1File(big);
            start(nodes, root, "Erin", tracker, 512, 0);
            PeerNode frank = start(nodes, root, "Frank", tracker, 0, 0);
            String bigInfo = frank.search("lecture").get(0).infoHash();
            Download d3 = frank.startDownload(frank.fetchMeta(bigInfo));
            long deadline = System.currentTimeMillis() + 30_000;
            while (d3.percent() < 35 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            d3.pause();
            int piecesBefore = d3.doneCount();
            frank.stop();

            PeerNode frankAgain = start(nodes, root, "Frank", tracker, 0, 0);
            SharedFile partial = frankAgain.registry().get(bigInfo);
            check("After restart, the unfinished download is found and its pieces re-verified on disk",
                    () -> partial != null && !partial.isComplete() && partial.haveCount() >= piecesBefore && piecesBefore > 0);
            Download d4 = frankAgain.startDownload(partial.meta());
            waitFor(d4, 60);
            check("Resumed download completes without re-downloading finished pieces",
                    () -> d4.state() == Download.State.COMPLETED && d4.initialPieces() > 0
                            && HashUtil.sha1File(d4.result()).equals(bigHash));
            System.out.println("      paused with " + piecesBefore + "/" + d3.meta().pieceCount() + " pieces, resumed with "
                    + d4.initialPieces());
        } finally {
            nodes.forEach(PeerNode::stop);
            tracker.stop();
            deleteRecursively(root);
        }

        System.out.println("\nResult: " + passed + " passed, " + failed + " failed");
        return failed == 0;
    }

    private static PeerNode start(List<PeerNode> nodes, Path root, String name, TrackerServer tracker, int limitKbps, int corrupt)
            throws IOException {
        PeerConfig c = new PeerConfig();
        c.name = name;
        c.port = NetUtil.freePort();
        c.sharedDir = root.resolve(name).resolve("shared");
        c.downloadsDir = root.resolve(name).resolve("downloads");
        c.trackerHost = "127.0.0.1";
        c.trackerPort = tracker.port();
        c.pieceKb = 64;
        c.uploadLimitKbps = limitKbps;
        c.corruptPercent = corrupt;
        c.lanDiscovery = false;
        PeerNode node = new PeerNode(c);
        node.start();
        nodes.add(node);
        return node;
    }

    private static void check(String name, Check check) {
        boolean ok;
        try {
            ok = check.run();
        } catch (Exception e) {
            ok = false;
            name += "  (" + e + ")";
        }
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + name);
        if (ok) {
            passed++;
        } else {
            failed++;
        }
    }

    private static void waitFor(Download d, int seconds) throws InterruptedException {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (d.state() == Download.State.RUNNING && System.currentTimeMillis() < end) {
            Thread.sleep(100);
        }
    }

    private static void writeRandom(Path file, int size, long seed) throws IOException {
        Files.createDirectories(file.getParent());
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        Files.write(file, data);
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // temp folder
                }
            });
        } catch (IOException ignored) {
            // temp folder
        }
    }
}
