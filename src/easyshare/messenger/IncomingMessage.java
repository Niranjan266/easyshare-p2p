package easyshare.messenger;

import java.time.LocalDateTime;
import java.util.List;

/** A message (optionally with attached files) received from another user. */
public final class IncomingMessage {
    /** A file attached to a message; metaText contains the piece hashes (see FileMeta). */
    public record Offer(String infoHash, String name, long size, String metaText) {
    }

    public final String id;
    public final String fromId;
    public final String fromName;
    public final String fromGroup;
    public final String fromHost;
    public final String fromAddress;
    public final int fromMessagePort;
    public final int fromPeerPort;
    public final String text;
    public final List<Offer> offers;
    public final LocalDateTime time = LocalDateTime.now();

    IncomingMessage(String id, String fromId, String fromName, String fromGroup, String fromHost, String fromAddress,
                    int fromMessagePort, int fromPeerPort, String text, List<Offer> offers) {
        this.id = id;
        this.fromId = fromId;
        this.fromName = fromName;
        this.fromGroup = fromGroup;
        this.fromHost = fromHost;
        this.fromAddress = fromAddress;
        this.fromMessagePort = fromMessagePort;
        this.fromPeerPort = fromPeerPort;
        this.text = text;
        this.offers = List.copyOf(offers);
    }
}
