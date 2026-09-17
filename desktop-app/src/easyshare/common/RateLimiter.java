package easyshare.common;

/**
 * Token-bucket upload limiter. Used to slow transfers down so that chunk exchange,
 * progress and pause/resume are visible during a demonstration on one computer.
 */
public final class RateLimiter {
    private final long bytesPerSecond;
    private long available;
    private long lastRefill = System.nanoTime();

    /** @param bytesPerSecond 0 means unlimited */
    public RateLimiter(long bytesPerSecond) {
        this.bytesPerSecond = bytesPerSecond;
        this.available = bytesPerSecond;
    }

    public boolean isLimited() {
        return bytesPerSecond > 0;
    }

    public void acquire(int bytes) throws InterruptedException {
        if (bytesPerSecond <= 0) {
            return;
        }
        long remaining = bytes;
        while (remaining > 0) {
            synchronized (this) {
                refill();
                long take = Math.min(available, remaining);
                available -= take;
                remaining -= take;
            }
            if (remaining > 0) {
                Thread.sleep(10);
            }
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsed = Math.min(now - lastRefill, 2_000_000_000L);
        long add = elapsed * bytesPerSecond / 1_000_000_000L;
        if (add > 0) {
            available = Math.min(bytesPerSecond, available + add);
            lastRefill = now;
        }
    }
}
