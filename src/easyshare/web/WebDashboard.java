package easyshare.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import easyshare.common.DaemonThreads;
import easyshare.common.Format;
import easyshare.common.HashUtil;
import easyshare.common.PeerAddress;
import easyshare.meta.FileMeta;
import easyshare.peer.Download;
import easyshare.peer.LanDiscovery;
import easyshare.peer.PeerClient;
import easyshare.peer.PeerNode;
import easyshare.peer.SharedFile;
import easyshare.tracker.TrackerClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Browser dashboard for a peer, served by Java's built-in HTTP server.
 * The page (dashboard.html) calls the JSON API below; all real work is done by {@link PeerNode}.
 * Bound to 127.0.0.1 so only the person at this computer can control the peer.
 */
public final class WebDashboard {
    private final PeerNode node;
    private final int port;
    private HttpServer server;

    public WebDashboard(PeerNode node, int port) {
        this.node = node;
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.setExecutor(Executors.newCachedThreadPool(DaemonThreads.named("web")));
        server.createContext("/", this::page);
        server.createContext("/api/status", ex -> api(ex, () -> status()));
        server.createContext("/api/peers", ex -> api(ex, () -> peers()));
        server.createContext("/api/search", ex -> api(ex, () -> search(query(ex).getOrDefault("q", ""))));
        server.createContext("/api/connect", ex -> api(ex, () -> connect(query(ex).getOrDefault("addr", ""))));
        server.createContext("/api/discover", ex -> api(ex, () -> discover()));
        server.createContext("/api/download", ex -> api(ex, () -> download(query(ex).getOrDefault("hash", ""))));
        server.createContext("/api/pause", ex -> api(ex, () -> pause(query(ex).getOrDefault("hash", ""))));
        server.createContext("/api/refresh", ex -> api(ex, () -> refresh()));
        server.createContext("/api/verify", ex -> api(ex, () -> verify(query(ex).getOrDefault("hash", ""))));
        server.start();
    }

    // ---------------------------------------------------------------- API handlers

    private Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", node.config().name);
        m.put("port", node.config().port);
        m.put("tracker", node.trackerAddress());
        m.put("trackerOnline", node.trackerReachable());
        m.put("lan", node.lanEnabled());
        m.put("sharedDir", node.config().sharedDir.toAbsolutePath().normalize().toString());
        m.put("downloadsDir", node.config().downloadsDir.toAbsolutePath().normalize().toString());
        m.put("pieceKb", node.config().pieceKb);
        m.put("limitKbps", node.config().uploadLimitKbps);
        m.put("corrupt", node.config().corruptPercent);
        m.put("uploaded", node.stats().uploaded());
        m.put("downloaded", node.stats().downloaded());
        m.put("uploadConnections", node.uploadConnections());

        List<Object> files = new ArrayList<>();
        for (SharedFile f : node.registry().list()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("infoHash", f.meta().infoHash());
            fm.put("name", f.meta().name());
            fm.put("size", f.meta().length());
            fm.put("sizeText", Format.bytes(f.meta().length()));
            fm.put("pieces", f.meta().pieceCount());
            fm.put("pieceSizeText", Format.bytes(f.meta().pieceLength()));
            fm.put("have", f.haveCount());
            fm.put("percent", f.percent());
            fm.put("complete", f.isComplete());
            fm.put("pieceMap", pieceMap(f.bitfield(), f.meta().pieceCount()));
            fm.put("path", f.path().toAbsolutePath().normalize().toString());
            files.add(fm);
        }
        m.put("files", files);

        List<Object> downloads = new ArrayList<>();
        for (Download d : node.downloads()) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("infoHash", d.meta().infoHash());
            dm.put("name", d.meta().name());
            dm.put("sizeText", Format.bytes(d.meta().length()));
            dm.put("state", d.state().name());
            dm.put("message", d.message());
            dm.put("percent", d.percent());
            dm.put("done", d.doneCount());
            dm.put("pieces", d.meta().pieceCount());
            dm.put("peers", d.activePeers());
            dm.put("badPieces", d.hashFailures());
            dm.put("bytes", d.bytesReceived());
            dm.put("elapsedMs", d.elapsedMs());
            dm.put("resumedPieces", d.initialPieces());
            dm.put("perPeer", d.piecesPerPeer());
            dm.put("events", d.recentEvents());
            dm.put("fileHash", d.meta().fileHash());
            dm.put("result", d.result() == null ? null : d.result().toAbsolutePath().normalize().toString());
            downloads.add(dm);
        }
        m.put("downloads", downloads);
        return m;
    }

    private Map<String, Object> peers() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> list = new ArrayList<>();
        String error = null;
        try {
            for (TrackerClient.PeerInfo p : node.trackerPeers()) {
                list.add(Map.of("name", p.name(), "address", p.address().toString(), "files", p.value(),
                        "you", node.isSelf(p.address())));
            }
        } catch (IOException e) {
            error = "Tracker not reachable: " + e.getMessage();
        }
        m.put("tracker", list);
        m.put("known", node.knownPeers().stream().map(PeerAddress::toString).toList());
        m.put("error", error);
        return m;
    }

    private Map<String, Object> search(String keyword) {
        List<Object> results = new ArrayList<>();
        for (PeerNode.SearchResult r : node.search(keyword)) {
            results.add(result(r));
        }
        return Map.of("results", results);
    }

    private Map<String, Object> connect(String addr) {
        try {
            PeerAddress address = PeerAddress.parse(addr, -1);
            try (PeerClient client = node.connect(address)) {
                List<Object> results = new ArrayList<>();
                for (PeerClient.RemoteFile f : client.listFiles()) {
                    results.add(result(new PeerNode.SearchResult(f.infoHash(), f.name(), f.length(), 1,
                            f.haveCount() == f.pieceCount() ? 1 : 0, node.registry().get(f.infoHash()) != null)));
                }
                node.addKnownPeer(address);
                return Map.of("ok", true, "peer", client.remoteName(), "results", results);
            }
        } catch (IOException | IllegalArgumentException e) {
            return error("Could not connect: " + e.getMessage());
        }
    }

    private Map<String, Object> discover() {
        try {
            List<Object> found = new ArrayList<>();
            for (LanDiscovery.Found f : node.discoverLan()) {
                found.add(Map.of("name", f.name(), "address", f.address().toString()));
            }
            return Map.of("ok", true, "found", found);
        } catch (IOException e) {
            return error("Discovery failed: " + e.getMessage());
        }
    }

    private Map<String, Object> download(String hash) {
        if (!HashUtil.isSha1Hex(hash)) {
            return error("Invalid info hash");
        }
        try {
            FileMeta meta = node.fetchMeta(hash);
            node.startDownload(meta);
            return Map.of("ok", true);
        } catch (IOException e) {
            return error(e.getMessage());
        }
    }

    private Map<String, Object> pause(String hash) {
        Download d = node.download(hash);
        if (d == null) {
            return error("No such download");
        }
        d.pause();
        return Map.of("ok", true);
    }

    private Map<String, Object> refresh() {
        try {
            return Map.of("ok", true, "count", node.refresh());
        } catch (IOException e) {
            return error(e.getMessage());
        }
    }

    private Map<String, Object> verify(String hash) {
        SharedFile f = node.registry().get(hash);
        if (f == null) {
            return error("File not found");
        }
        try {
            int ok = 0;
            int bad = 0;
            int missing = 0;
            for (int i = 0; i < f.meta().pieceCount(); i++) {
                byte[] data = f.readPiece(i);
                if (data == null) {
                    missing++;
                } else if (HashUtil.sha1Hex(data).equals(f.meta().pieceHash(i))) {
                    ok++;
                } else {
                    bad++;
                }
            }
            Map<String, Object> m = new HashMap<>();
            m.put("ok", true);
            m.put("piecesOk", ok);
            m.put("piecesBad", bad);
            m.put("piecesMissing", missing);
            m.put("expected", f.meta().fileHash());
            if (f.isComplete()) {
                String actual = HashUtil.sha1File(f.path());
                m.put("actual", actual);
                m.put("verified", actual.equals(f.meta().fileHash()));
            }
            return m;
        } catch (IOException e) {
            return error(e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> result(PeerNode.SearchResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("infoHash", r.infoHash());
        m.put("name", r.name());
        m.put("sizeText", Format.bytes(r.length()));
        m.put("peers", r.peers());
        m.put("seeders", r.seeders());
        m.put("local", r.local());
        return m;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("ok", false);
        m.put("error", message);
        return m;
    }

    /** One character per piece ('1' = have, '0' = missing), grouped when a file has many pieces. */
    private static String pieceMap(BitSet have, int count) {
        int cells = Math.min(count, 400);
        StringBuilder sb = new StringBuilder(cells);
        for (int c = 0; c < cells; c++) {
            int from = (int) ((long) c * count / cells);
            int to = (int) ((long) (c + 1) * count / cells);
            boolean all = true;
            for (int i = from; i < to; i++) {
                all &= have.get(i);
            }
            sb.append(all ? '1' : '0');
        }
        return sb.toString();
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> params = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return params;
    }

    private void page(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            send(ex, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = WebDashboard.class.getResourceAsStream("dashboard.html")) {
            if (in == null) {
                send(ex, 500, "text/plain", "dashboard.html missing from the build".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
        }
    }

    private interface Handler {
        Object handle();
    }

    private static void api(HttpExchange ex, Handler handler) throws IOException {
        Object body;
        try {
            body = handler.handle();
        } catch (RuntimeException e) {
            body = error("Internal error: " + e);
        }
        send(ex, 200, "application/json; charset=utf-8", Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
