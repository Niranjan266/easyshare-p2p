package easyshare.peer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Total bytes uploaded and downloaded by this peer. */
public final class TransferStats {
    private final AtomicLong uploaded = new AtomicLong();
    private final AtomicLong downloaded = new AtomicLong();
    private final Map<String, AtomicLong> uploadedPerFile = new ConcurrentHashMap<>();

    void addUploaded(String infoHash, long bytes) {
        uploaded.addAndGet(bytes);
        uploadedPerFile.computeIfAbsent(infoHash, k -> new AtomicLong()).addAndGet(bytes);
    }

    /** Bytes of one file (info hash) uploaded to all peers so far. */
    public long uploaded(String infoHash) {
        AtomicLong value = uploadedPerFile.get(infoHash);
        return value == null ? 0 : value.get();
    }

    void addDownloaded(long bytes) {
        downloaded.addAndGet(bytes);
    }

    public long uploaded() {
        return uploaded.get();
    }

    public long downloaded() {
        return downloaded.get();
    }
}
