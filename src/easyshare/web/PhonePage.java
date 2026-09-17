package easyshare.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import easyshare.common.DaemonThreads;
import easyshare.common.Format;
import easyshare.common.Log;
import easyshare.meta.FileMeta;
import easyshare.peer.PeerNode;
import easyshare.peer.SharedFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Phone page: lets any phone (Android or iPhone) on the same Wi-Fi download this peer's
 * shared files and upload files into the shared folder, using only a web browser.
 * Unlike the dashboard, it listens on all network interfaces, so only use it on a trusted network.
 */
public final class PhonePage {
    private final PeerNode node;
    private final int port;

    public PhonePage(PeerNode node, int port) {
        this.node = node;
        this.port = port;
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool(DaemonThreads.named("phone")));
        server.createContext("/", this::page);
        server.createContext("/download/", this::download);
        server.createContext("/upload", this::upload);
        server.start();
    }

    private void page(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        StringBuilder rows = new StringBuilder();
        List<SharedFile> files = node.registry().list().stream().filter(SharedFile::isComplete).toList();
        for (SharedFile f : files) {
            rows.append("<a class=\"file\" href=\"/download/").append(f.meta().infoHash()).append("\">")
                    .append("<span class=\"name\">").append(html(f.meta().name())).append("</span>")
                    .append("<span class=\"meta\">").append(Format.bytes(f.meta().length())).append(" &middot; ")
                    .append(f.meta().pieceCount()).append(" pieces &middot; SHA-1 ")
                    .append(f.meta().fileHash(), 0, 12).append("&hellip;</span>")
                    .append("<span class=\"get\">Download</span></a>");
        }
        if (files.isEmpty()) {
            rows.append("<p class=\"empty\">No files shared yet. Upload one below, or copy files into the shared folder on the PC and type <b>refresh</b>.</p>");
        }
        String body = TEMPLATE.replace("{{NAME}}", html(node.config().name)).replace("{{FILES}}", rows.toString());
        send(ex, 200, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private void download(HttpExchange ex) throws IOException {
        String hash = ex.getRequestURI().getPath().substring("/download/".length());
        SharedFile f = node.registry().get(hash);
        if (f == null || !f.isComplete()) {
            send(ex, 404, "text/plain; charset=utf-8", "File not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Path path = f.path();
        String name = f.meta().name();
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name.replaceAll("[^\\x20-\\x7e]|\"", "_")
                + "\"; filename*=UTF-8''" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
        ex.sendResponseHeaders(200, Files.size(path));
        try (OutputStream out = ex.getResponseBody()) {
            Files.copy(path, out);
        }
        Log.info("PHONE", "Sent " + name + " (" + Format.bytes(f.meta().length()) + ") to " + ex.getRemoteAddress().getAddress().getHostAddress());
    }

    private void upload(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            send(ex, 405, "text/plain; charset=utf-8", "Use POST".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String raw = ex.getRequestURI().getRawQuery();
        String name = null;
        if (raw != null && raw.startsWith("name=")) {
            name = FileMeta.sanitizeName(URLDecoder.decode(raw.substring(5), StandardCharsets.UTF_8));
        }
        if (name == null || name.startsWith(".") || name.endsWith(".part") || name.endsWith(FileMeta.EXTENSION)) {
            send(ex, 400, "text/plain; charset=utf-8", "Invalid file name".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Path folder = node.config().sharedDir.toAbsolutePath().normalize();
        Files.createDirectories(folder);
        Path temp = Files.createTempFile(folder, ".upload-", ".tmp");
        try (InputStream in = ex.getRequestBody()) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            Path target = unique(folder.resolve(name));
            Files.move(temp, target);
            Log.info("PHONE", "Received " + target.getFileName() + " (" + Format.bytes(Files.size(target)) + ") from "
                    + ex.getRemoteAddress().getAddress().getHostAddress() + " - now shared with all peers");
            node.refresh();
            send(ex, 200, "text/plain; charset=utf-8", ("Uploaded " + target.getFileName()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            send(ex, 500, "text/plain; charset=utf-8", ("Upload failed: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Path unique(Path p) {
        if (!Files.exists(p)) {
            return p;
        }
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            Path candidate = p.resolveSibling(base + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static final String TEMPLATE = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>EasyShare - {{NAME}}</title>
            <style>
              :root { --bg:#f4f5f7; --card:#fff; --text:#1d2330; --muted:#6b7280; --line:#e3e6eb; --accent:#2563eb; --ok:#15803d; }
              @media (prefers-color-scheme: dark) { :root { --bg:#0f1218; --card:#181c24; --text:#e6e8ec; --muted:#9aa3b2; --line:#2a303b; --accent:#6b9bff; --ok:#4ade80; } }
              body { margin:0; background:var(--bg); color:var(--text); font:16px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif; }
              main { max-width:640px; margin:0 auto; padding:20px 16px 40px; }
              h1 { font-size:22px; margin:4px 0 2px; } h1 span { color:var(--accent); }
              .sub { color:var(--muted); margin:0 0 20px; font-size:14px; }
              h2 { font-size:16px; margin:26px 0 10px; }
              .file { display:grid; grid-template-columns:1fr auto; gap:2px 12px; align-items:center; background:var(--card); border:1px solid var(--line);
                      border-radius:12px; padding:14px 16px; margin-bottom:10px; text-decoration:none; color:var(--text); }
              .name { font-weight:600; word-break:break-all; } .meta { color:var(--muted); font-size:13px; grid-column:1; }
              .get { grid-row:1 / span 2; grid-column:2; background:var(--accent); color:#fff; border-radius:8px; padding:8px 14px; font-size:14px; font-weight:600; }
              .empty { color:var(--muted); }
              .upload { background:var(--card); border:1px solid var(--line); border-radius:12px; padding:16px; }
              input[type=file] { width:100%; margin-bottom:12px; font-size:15px; }
              button { width:100%; background:var(--accent); color:#fff; border:0; border-radius:8px; padding:12px; font-size:16px; font-weight:600; }
              button:disabled { opacity:.5; }
              progress { width:100%; height:10px; margin-top:12px; }
              #status { margin-top:8px; font-size:14px; color:var(--muted); }
              #status.ok { color:var(--ok); }
            </style></head>
            <body><main>
              <h1>Easy<span>Share</span> &middot; {{NAME}}</h1>
              <p class="sub">Files shared by this peer. Tap a file to download it to your phone.</p>
              <h2>Download to this phone</h2>
              {{FILES}}
              <h2>Send a file from this phone</h2>
              <div class="upload">
                <input type="file" id="file">
                <button id="send">Upload to {{NAME}}</button>
                <progress id="bar" value="0" max="100" hidden></progress>
                <div id="status"></div>
              </div>
            </main>
            <script>
              const bar = document.getElementById('bar'), status = document.getElementById('status'), btn = document.getElementById('send');
              btn.onclick = () => {
                const f = document.getElementById('file').files[0];
                if (!f) { status.textContent = 'Choose a file first.'; return; }
                const xhr = new XMLHttpRequest();
                xhr.open('POST', '/upload?name=' + encodeURIComponent(f.name));
                xhr.upload.onprogress = e => { if (e.lengthComputable) bar.value = e.loaded * 100 / e.total; };
                xhr.onload = () => { btn.disabled = false; status.className = xhr.status === 200 ? 'ok' : '';
                  status.textContent = xhr.responseText; if (xhr.status === 200) setTimeout(() => location.reload(), 1200); };
                xhr.onerror = () => { btn.disabled = false; status.textContent = 'Upload failed - is the PC still running EasyShare?'; };
                btn.disabled = true; bar.hidden = false; bar.value = 0; status.className = ''; status.textContent = 'Uploading ' + f.name + '...';
                xhr.send(f);
              };
            </script>
            </body></html>
            """;
}
