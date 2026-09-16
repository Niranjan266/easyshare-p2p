package easyshare.peer;

import easyshare.common.DaemonThreads;
import easyshare.common.Log;
import easyshare.common.NetUtil;
import easyshare.common.PeerAddress;
import easyshare.common.RateLimiter;
import easyshare.meta.FileMeta;
import easyshare.tracker.TrackerClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The core of one peer (no user interface): shared files, upload server, discovery and downloads.
 * Used by the console ({@link PeerCli}), the web dashboard and the self-test.
 */
public final class PeerNode {
    private static final long HEARTBEAT_SECONDS = 15;

    private final PeerConfig config;
    private final String peerId;
    private final FileRegistry registry = new FileRegistry();
    private final TransferStats stats = new TransferStats();
    private final PeerServer server;
    private final TrackerClient tracker;
    private final Set<PeerAddress> knownPeers = ConcurrentHashMap.newKeySet();
    private final Map<String, Download> downloads = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(DaemonThreads.named("heartbeat"));
    private final AtomicBoolean stopped = new AtomicBoolean();
    private LanDiscovery lan;
    private volatile boolean trackerReachable = true;

    public record SearchResult(String infoHash, String name, long length, int peers, int seeders, boolean local) {
    }

    public PeerNode(PeerConfig config) {
        this.config = config;
        byte[] id = new byte[8];
        new SecureRandom().nextBytes(id);
        this.peerId = easyshare.common.HashUtil.toHex(id);
        this.server = new PeerServer(this, new RateLimiter(config.uploadLimitKbps * 1024L));
        this.tracker = config.trackerHost == null ? null : new TrackerClient(config.trackerHost, config.trackerPort);
    }

    public void start() throws IOException {
        Files.createDirectories(config.downloadsDir);
        Files.createDirectories(stateDir());
        registry.scanFolder(config.sharedDir, config.pieceKb * 1024);
        registry.loadDownloads(config.downloadsDir, stateDir());
        server.start(config.port);
        if (config.lanDiscovery) {
            try {
                lan = new LanDiscovery(peerId, config.name, config.port);
                lan.start();
            } catch (IOException e) {
                lan = null;
                Log.info("LAN", "LAN discovery is not available: " + e.getMessage());
            }
        }
        if (tracker != null) {
            announce();
            scheduler.scheduleWithFixedDelay(this::announceSafely, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        }
    }

    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        downloads.values().forEach(Download::pause);
        if (tracker != null) {
            try {
                tracker.unregister(advertisedHost(), config.port);
            } catch (IOException ignored) {
                // tracker offline; it will time us out
            }
        }
        server.stop();
        if (lan != null) {
            lan.close();
        }
        registry.closeAll();
    }

    /** Heartbeat: tell the tracker we are online and which files (and how much of them) we have. */
    public void announce() {
        if (tracker == null) {
            return;
        }
        try {
            List<TrackerClient.Announcement> list = new ArrayList<>();
            for (SharedFile f : registry.list()) {
                list.add(new TrackerClient.Announcement(f.meta().infoHash(), f.meta().length(), f.percent(), f.meta().name()));
            }
            tracker.announce(config.name, advertisedHost(), config.port, list);
            if (!trackerReachable) {
                trackerReachable = true;
                Log.info("TRACKER", "Connected to tracker " + tracker.address());
            }
        } catch (IOException e) {
            if (trackerReachable) {
                trackerReachable = false;
                Log.info("TRACKER", "Tracker " + tracker.address() + " is not reachable (" + e.getMessage()
                        + "). Retrying every " + HEARTBEAT_SECONDS + " s. LAN discovery and 'connect' still work.");
            }
        }
    }

    private void announceSafely() {
        try {
            announce();
        } catch (RuntimeException e) {
            Log.info("TRACKER", "Heartbeat error: " + e);
        }
    }

    public int refresh() throws IOException {
        int count = registry.scanFolder(config.sharedDir, config.pieceKb * 1024);
        announce();
        return count;
    }

    // ---------------------------------------------------------------- discovery

    public void addKnownPeer(PeerAddress address) {
        if (!isSelf(address)) {
            knownPeers.add(address);
        }
    }

    public Set<PeerAddress> knownPeers() {
        return Set.copyOf(knownPeers);
    }

    public List<TrackerClient.PeerInfo> trackerPeers() throws IOException {
        if (tracker == null) {
            return List.of();
        }
        return tracker.peers();
    }

    public List<LanDiscovery.Found> discoverLan() throws IOException {
        List<LanDiscovery.Found> found = LanDiscovery.discover(peerId, 2000);
        for (LanDiscovery.Found f : found) {
            addKnownPeer(f.address());
        }
        return found;
    }

    public boolean isSelf(PeerAddress address) {
        if (address.port() != config.port) {
            return false;
        }
        if (config.advertisedHost != null && config.advertisedHost.equals(address.host())) {
            return true;
        }
        return NetUtil.isLocalAddress(address.host());
    }

    /** All peers that may have a file: from the tracker plus manually added / LAN-discovered peers. */
    public Set<PeerAddress> findSources(String infoHash) {
        Set<PeerAddress> sources = new LinkedHashSet<>();
        if (tracker != null) {
            try {
                for (TrackerClient.PeerInfo p : tracker.peersWith(infoHash)) {
                    sources.add(p.address());
                }
            } catch (IOException ignored) {
                // tracker offline
            }
        }
        sources.addAll(knownPeers);
        sources.removeIf(this::isSelf);
        return sources;
    }

    /** Searches the tracker and all known peers. An empty keyword lists everything. */
    public List<SearchResult> search(String keyword) {
        String needle = keyword.trim().toLowerCase();
        Map<String, SearchResult> results = new LinkedHashMap<>();
        Map<String, Set<String>> directHolders = new LinkedHashMap<>();
        Map<String, Set<String>> directSeeders = new LinkedHashMap<>();
        if (tracker != null) {
            try {
                for (TrackerClient.SearchHit h : tracker.search(needle)) {
                    results.put(h.infoHash(), new SearchResult(h.infoHash(), h.name(), h.length(), h.holders(), h.seeders(),
                            registry.get(h.infoHash()) != null));
                }
            } catch (IOException e) {
                Log.info("SEARCH", "Tracker not reachable: " + e.getMessage());
            }
        }
        for (PeerAddress address : knownPeers) {
            try (PeerClient client = connect(address)) {
                for (PeerClient.RemoteFile f : client.listFiles()) {
                    if (needle.isEmpty() || needle.equals("*") || f.name().toLowerCase().contains(needle)) {
                        directHolders.computeIfAbsent(f.infoHash(), k -> new HashSet<>()).add(client.remoteId());
                        boolean seeder = f.haveCount() == f.pieceCount();
                        if (seeder) {
                            directSeeders.computeIfAbsent(f.infoHash(), k -> new HashSet<>()).add(client.remoteId());
                        }
                        results.merge(f.infoHash(),
                                new SearchResult(f.infoHash(), f.name(), f.length(), 1, seeder ? 1 : 0, registry.get(f.infoHash()) != null),
                                (a, b) -> a);
                    }
                }
            } catch (IOException ignored) {
                // peer offline
            }
        }
        // the same peer can be reached through the tracker and directly: keep the larger (de-duplicated) count
        directHolders.forEach((hash, holders) -> {
            SearchResult r = results.get(hash);
            int seeders = directSeeders.getOrDefault(hash, Set.of()).size();
            if (r.peers() < holders.size() || r.seeders() < seeders) {
                results.put(hash, new SearchResult(r.infoHash(), r.name(), r.length(), Math.max(r.peers(), holders.size()),
                        Math.max(r.seeders(), seeders), r.local()));
            }
        });
        List<SearchResult> list = new ArrayList<>(results.values());
        list.sort(Comparator.comparing((SearchResult r) -> r.name().toLowerCase()));
        return list;
    }

    public PeerClient connect(PeerAddress address) throws IOException {
        return PeerClient.connect(address, peerId, config.name, 4000);
    }

    // ---------------------------------------------------------------- downloads

    /** Gets the metadata (piece hashes) of a file from any peer and checks it matches the info hash. */
    public FileMeta fetchMeta(String infoHash) throws IOException {
        SharedFile local = registry.get(infoHash);
        if (local != null) {
            return local.meta();
        }
        for (PeerAddress address : findSources(infoHash)) {
            try (PeerClient client = connect(address)) {
                String text = client.getMeta(infoHash);
                if (text == null) {
                    continue;
                }
                FileMeta meta = FileMeta.parse(text);
                if (meta.infoHash().equals(infoHash)) {
                    return meta;
                }
                Log.info("SECURITY", "Peer " + address + " sent metadata that does not match the info hash - ignored");
            } catch (IOException ignored) {
                // try the next peer
            }
        }
        throw new IOException("No online peer has this file");
    }

    public synchronized Download startDownload(FileMeta meta) throws IOException {
        Download existing = downloads.get(meta.infoHash());
        if (existing != null && existing.state() == Download.State.RUNNING) {
            return existing;
        }
        SharedFile target = registry.get(meta.infoHash());
        if (target != null && target.isComplete()) {
            throw new IOException("You already have this file: " + target.path().toAbsolutePath());
        }
        if (target == null) {
            meta.save(stateDir().resolve(meta.infoHash() + FileMeta.EXTENSION));
            target = SharedFile.createPartial(meta, FileRegistry.partPath(config.downloadsDir, meta),
                    config.downloadsDir.resolve(meta.name()));
            registry.add(target);
        }
        Download download = new Download(this, meta, target);
        downloads.put(meta.infoHash(), download);
        download.start();
        announce();
        return download;
    }

    void downloadFinished(Download download) {
        try {
            Files.writeString(stateDir().resolve(download.meta().infoHash() + ".done"),
                    download.result().getFileName().toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.info("DOWNLOAD", "Could not save completion state: " + e.getMessage());
        }
        announce();
    }

    /** Unfinished downloads (running, paused, or found on disk after a restart). */
    public List<SharedFile> unfinished() {
        return registry.list().stream().filter(f -> !f.isComplete()).toList();
    }

    public Download download(String infoHash) {
        return downloads.get(infoHash);
    }

    public List<Download> downloads() {
        return new ArrayList<>(downloads.values());
    }

    // ---------------------------------------------------------------- accessors

    public Path stateDir() {
        return config.downloadsDir.resolve(".easyshare");
    }

    String advertisedHost() {
        if (config.advertisedHost != null) {
            return config.advertisedHost;
        }
        return "-"; // tracker uses the address our connection comes from
    }

    public PeerConfig config() {
        return config;
    }

    public String peerId() {
        return peerId;
    }

    public FileRegistry registry() {
        return registry;
    }

    public TransferStats stats() {
        return stats;
    }

    public boolean hasTracker() {
        return tracker != null;
    }

    public boolean trackerReachable() {
        return tracker != null && trackerReachable;
    }

    public String trackerAddress() {
        return tracker == null ? "none" : tracker.address();
    }

    public boolean lanEnabled() {
        return lan != null;
    }

    public int uploadConnections() {
        return server.activeConnections();
    }
}
