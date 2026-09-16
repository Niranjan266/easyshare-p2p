package easyshare.peer;

import easyshare.common.DaemonThreads;
import easyshare.common.Format;
import easyshare.common.HashUtil;
import easyshare.common.Log;
import easyshare.common.PeerAddress;
import easyshare.meta.FileMeta;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One multi-source download. A coordinator thread finds peers that have the file and starts
 * one worker thread per peer. Every worker repeatedly asks the shared {@link PieceManager} for a piece,
 * downloads it, checks its SHA-1 hash and writes it to the ".part" file. Pieces with a wrong hash
 * are thrown away and requested again (possibly from another peer).
 */
public final class Download {
    public enum State { RUNNING, COMPLETED, PAUSED, FAILED }

    private static final long SOURCE_REFRESH_MS = 5_000;
    private static final long RETRY_DELAY_MS = 8_000;
    private static final long STALL_TIMEOUT_MS = 25_000;
    private static final int MAX_BAD_PIECES_PER_PEER = 5;

    private final PeerNode node;
    private final FileMeta meta;
    private final SharedFile target;
    private final PieceManager pieces;
    private final ExecutorService workers;
    private final Set<PeerAddress> active = ConcurrentHashMap.newKeySet();
    private final Set<PeerAddress> blacklist = ConcurrentHashMap.newKeySet();
    private final Map<PeerAddress, Long> retryAt = new ConcurrentHashMap<>();
    private final Set<PeerClient> openClients = ConcurrentHashMap.newKeySet();
    private final Set<String> connectedPeerIds = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> piecesPerPeer = new ConcurrentSkipListMap<>();
    private final Queue<String> events = new ConcurrentLinkedQueue<>();
    private final Deque<String> recent = new ArrayDeque<>();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicInteger hashFailures = new AtomicInteger();
    private final int initialPieces;
    private final long startedAt = System.currentTimeMillis();
    private volatile long endedAt;
    private volatile State state = State.RUNNING;
    private volatile String message = "Looking for peers...";
    private volatile Path result;

    Download(PeerNode node, FileMeta meta, SharedFile target) {
        this.node = node;
        this.meta = meta;
        this.target = target;
        this.pieces = new PieceManager(target.bitfield(), meta.pieceCount());
        this.initialPieces = target.haveCount();
        this.workers = Executors.newCachedThreadPool(DaemonThreads.named("download-" + meta.infoHash().substring(0, 6)));
    }

    void start() {
        Thread coordinator = new Thread(this::coordinate, "download-coordinator");
        coordinator.setDaemon(true);
        coordinator.start();
    }

    private void coordinate() {
        long lastRefresh = 0;
        long lastProgress = System.currentTimeMillis();
        int lastDone = pieces.doneCount();
        try {
            while (state == State.RUNNING) {
                if (pieces.allDone()) {
                    finish();
                    break;
                }
                long now = System.currentTimeMillis();
                if (now - lastRefresh >= SOURCE_REFRESH_MS) {
                    lastRefresh = now;
                    connectToNewSources(now);
                }
                int done = pieces.doneCount();
                if (done != lastDone) {
                    lastDone = done;
                    lastProgress = now;
                    message = "Downloading";
                } else if (active.isEmpty() && now - lastProgress > STALL_TIMEOUT_MS) {
                    message = "No online peer has the missing pieces. Paused at " + percent() + "% - type 'resume' later.";
                    state = State.PAUSED;
                    endedAt = now;
                    event(message);
                }
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            stopWorkers();
        }
    }

    private void connectToNewSources(long now) {
        for (PeerAddress address : node.findSources(meta.infoHash())) {
            if (state != State.RUNNING) {
                return;
            }
            Long retry = retryAt.get(address);
            if (blacklist.contains(address) || (retry != null && retry > now) || !active.add(address)) {
                continue;
            }
            try {
                workers.submit(() -> runWorker(address));
            } catch (RejectedExecutionException e) {
                active.remove(address);
                return;
            }
        }
    }

    /** Worker thread: downloads pieces from one peer until the file is complete. */
    private void runWorker(PeerAddress address) {
        PeerClient client = null;
        BitSet peerHas = null;
        int current = -1;
        int badPieces = 0;
        String label = address.toString();
        String remoteId = null;
        try {
            client = PeerClient.connect(address, node.peerId(), node.config().name, 4000);
            openClients.add(client);
            if (client.remoteId().equals(node.peerId()) || !connectedPeerIds.add(client.remoteId())) {
                return; // that is ourselves, or a peer we already download from under another address
            }
            remoteId = client.remoteId();
            label = client.remoteName() + " (" + address + ")";
            peerHas = client.handshake(meta.infoHash(), meta.pieceCount());
            if (peerHas == null) {
                return; // peer does not have this file
            }
            pieces.addAvailability(peerHas);
            int idleRounds = 0;

            while (state == State.RUNNING && !pieces.allDone()) {
                current = pieces.pick(peerHas);
                if (current < 0) {
                    // peer has nothing we still need right now; it may get new pieces later
                    if (++idleRounds > 10) {
                        break;
                    }
                    Thread.sleep(1500);
                    BitSet fresh = client.bitfield(meta.pieceCount());
                    pieces.removeAvailability(peerHas);
                    pieces.addAvailability(fresh);
                    peerHas = fresh;
                    continue;
                }
                idleRounds = 0;
                int index = current;
                byte[] data = client.requestPiece(index, meta.pieceSize(index));
                if (data == null) {
                    pieces.release(index);
                    current = -1;
                    continue;
                }
                if (!HashUtil.sha1Hex(data).equals(meta.pieceHash(index))) {
                    pieces.release(index);
                    current = -1;
                    hashFailures.incrementAndGet();
                    event("Piece #" + index + " from " + label + " failed SHA-1 check -> discarded, will download again");
                    if (++badPieces >= MAX_BAD_PIECES_PER_PEER) {
                        blacklist.add(address);
                        event(label + " sent " + badPieces + " corrupted pieces -> peer blocked for this download");
                        break;
                    }
                    continue;
                }
                try {
                    target.writePiece(index, data);
                } catch (IOException e) {
                    fail("Cannot write to disk: " + e.getMessage());
                    return;
                }
                pieces.completed(index);
                current = -1;
                bytesReceived.addAndGet(data.length);
                node.stats().addDownloaded(data.length);
                piecesPerPeer.computeIfAbsent(label, k -> new AtomicInteger()).incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (state == State.RUNNING && peerHas != null) {
                event("Lost connection to " + label + " (" + e.getMessage() + ")");
            }
        } finally {
            if (current >= 0) {
                pieces.release(current);
            }
            if (peerHas != null) {
                pieces.removeAvailability(peerHas);
            }
            if (client != null) {
                openClients.remove(client);
                client.close();
            }
            if (remoteId != null) {
                connectedPeerIds.remove(remoteId);
            }
            retryAt.put(address, System.currentTimeMillis() + RETRY_DELAY_MS);
            active.remove(address);
        }
    }

    private void finish() {
        stopWorkers();
        message = "Verifying whole-file SHA-1...";
        try {
            result = target.finish();
            endedAt = System.currentTimeMillis();
            state = State.COMPLETED;
            message = "Completed";
            node.downloadFinished(this);
            Log.info("DOWNLOAD", "Completed " + meta.name() + " (" + Format.bytes(meta.length()) + ") -> " + result.toAbsolutePath());
        } catch (IOException e) {
            fail("Final verification failed: " + e.getMessage());
        }
    }

    private void fail(String reason) {
        message = reason;
        endedAt = System.currentTimeMillis();
        state = State.FAILED;
        event(reason);
        stopWorkers();
    }

    /** Stops the download; the ".part" file and all verified pieces are kept for resuming. */
    public void pause() {
        if (state != State.RUNNING) {
            return;
        }
        state = State.PAUSED;
        endedAt = System.currentTimeMillis();
        message = "Paused at " + percent() + "% - type 'resume' to continue";
        stopWorkers();
    }

    private void stopWorkers() {
        workers.shutdownNow();
        for (PeerClient c : openClients) {
            c.abort();
        }
    }

    public String pollEvent() {
        return events.poll();
    }

    /** Last events for the web dashboard (not consumed like {@link #pollEvent()}). */
    public List<String> recentEvents() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    private void event(String text) {
        events.add(text);
        synchronized (recent) {
            recent.addLast(text);
            while (recent.size() > 15) {
                recent.removeFirst();
            }
        }
    }

    public FileMeta meta() {
        return meta;
    }

    public SharedFile target() {
        return target;
    }

    public State state() {
        return state;
    }

    public String message() {
        return message;
    }

    public Path result() {
        return result;
    }

    public int doneCount() {
        return pieces.doneCount();
    }

    public int percent() {
        return (int) (pieces.doneCount() * 100L / meta.pieceCount());
    }

    public int initialPieces() {
        return initialPieces;
    }

    public int activePeers() {
        return active.size();
    }

    public int hashFailures() {
        return hashFailures.get();
    }

    public long bytesReceived() {
        return bytesReceived.get();
    }

    public long elapsedMs() {
        return (state == State.RUNNING ? System.currentTimeMillis() : endedAt) - startedAt;
    }

    public Map<String, Integer> piecesPerPeer() {
        Map<String, Integer> copy = new LinkedHashMap<>();
        piecesPerPeer.forEach((k, v) -> copy.put(k, v.get()));
        return copy;
    }
}
