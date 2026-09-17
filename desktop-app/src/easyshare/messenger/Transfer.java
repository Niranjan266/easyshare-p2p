package easyshare.messenger;

import easyshare.peer.Download;

import java.nio.file.Path;

/** One file being sent to or received from another user (shown in the Transfers table). */
public final class Transfer {
    public enum Direction { SEND, RECEIVE }

    public final Direction direction;
    public final String userId;
    public final String userName;
    public final String infoHash;
    public final String fileName;
    public final long size;
    final IncomingMessage message;
    volatile Download download;
    volatile String status;
    volatile boolean finished;
    volatile boolean ok;
    volatile Path result;
    volatile long uploadedAtStart;

    Transfer(Direction direction, String userId, String userName, String infoHash, String fileName, long size,
             IncomingMessage message, String status) {
        this.direction = direction;
        this.userId = userId;
        this.userName = userName;
        this.infoHash = infoHash;
        this.fileName = fileName;
        this.size = size;
        this.message = message;
        this.status = status;
    }

    public String status() {
        return status;
    }

    public boolean finished() {
        return finished;
    }

    public boolean ok() {
        return ok;
    }

    public Path result() {
        return result;
    }

    public boolean canRetry() {
        return direction == Direction.RECEIVE && finished && !ok;
    }
}
