package easyshare.peer;

import easyshare.common.PeerAddress;

import java.nio.file.Path;

/** Command-line options of a peer. */
public final class PeerConfig {
    public String name = "Peer";
    public int port = 6001;
    public Path sharedDir = Path.of("shared");
    public Path downloadsDir = Path.of("downloads");
    public String trackerHost;
    public int trackerPort = 7000;
    public int pieceKb = 256;
    public int uploadLimitKbps = 0;
    public int corruptPercent = 0;
    public String advertisedHost;
    public boolean lanDiscovery = true;
    /** Port of the built-in web dashboard, 0 = disabled. */
    public int webPort = 0;
    /** Port of the phone page (download/upload from a phone browser), 0 = disabled. */
    public int phonePort = 0;

    public static final String OPTIONS = """
              --name <name>          Display name of this peer                  (default Peer)
              --port <port>          TCP port for other peers to connect        (default 6001)
              --shared <folder>      Folder whose files you share               (default shared)
              --downloads <folder>   Folder where downloads are saved           (default downloads)
              --tracker <ip:port>    Tracker used for peer discovery            (optional)
              --web <port>           Open the web dashboard on this port        (optional, e.g. 8001)
              --phone <port>         Phone page for phones on the same Wi-Fi    (optional, e.g. 9001)
              --piece <KB>           Piece (chunk) size in KB                   (default 256)
              --limit <KB/s>         Upload speed limit, to watch transfers     (default unlimited)
              --corrupt <percent>    SIMULATION: corrupt % of uploaded pieces   (default 0)
              --host <ip>            IP address to advertise to the tracker     (auto)
              --no-lan               Disable LAN multicast discovery
            """;

    public static PeerConfig parse(String[] args) {
        PeerConfig c = new PeerConfig();
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (option.equals("--no-lan")) {
                c.lanDiscovery = false;
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            String v = args[++i];
            switch (option) {
                case "--name" -> c.name = v;
                case "--port" -> c.port = number(option, v, 1, 65535);
                case "--shared" -> c.sharedDir = Path.of(v);
                case "--downloads" -> c.downloadsDir = Path.of(v);
                case "--tracker" -> {
                    PeerAddress a = PeerAddress.parse(v, 7000);
                    c.trackerHost = a.host();
                    c.trackerPort = a.port();
                }
                case "--web" -> c.webPort = number(option, v, 1, 65535);
                case "--phone" -> c.phonePort = number(option, v, 1, 65535);
                case "--piece" -> c.pieceKb = number(option, v, 1, 8192);
                case "--limit" -> c.uploadLimitKbps = number(option, v, 0, 10_000_000);
                case "--corrupt" -> c.corruptPercent = number(option, v, 0, 100);
                case "--host" -> c.advertisedHost = v;
                default -> throw new IllegalArgumentException("Unknown option: " + option);
            }
        }
        if (c.sharedDir.toAbsolutePath().normalize().equals(c.downloadsDir.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("--shared and --downloads must be different folders");
        }
        return c;
    }

    private static int number(String option, String value, int min, int max) {
        try {
            int n = Integer.parseInt(value);
            if (n < min || n > max) {
                throw new IllegalArgumentException(option + " must be between " + min + " and " + max);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " expects a number, got '" + value + "'");
        }
    }
}
