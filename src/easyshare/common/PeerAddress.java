package easyshare.common;

/** Network address (host + TCP port) of a peer. */
public record PeerAddress(String host, int port) {

    /** Parses "host:port" or just "host" (then defaultPort is used; pass -1 to make the port required). */
    public static PeerAddress parse(String text, int defaultPort) {
        String s = text.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Address is empty");
        }
        String host = s;
        int port = defaultPort;
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon) {
            host = s.substring(0, colon);
            try {
                port = Integer.parseInt(s.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid port in '" + text + "'");
            }
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Address must look like IP:PORT, e.g. 192.168.1.10:6001");
        }
        return new PeerAddress(host, port);
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
