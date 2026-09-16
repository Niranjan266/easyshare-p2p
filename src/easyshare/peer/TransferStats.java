package easyshare.peer;

import java.util.concurrent.atomic.AtomicLong;

/** Total bytes uploaded and downloaded by this peer. */
public final class TransferStats {
    private final AtomicLong uploaded = new AtomicLong();
    private final AtomicLong downloaded = new AtomicLong();

    void addUploaded(long bytes) {
        uploaded.addAndGet(bytes);
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
