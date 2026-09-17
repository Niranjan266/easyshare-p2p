package easyshare.tracker;

import easyshare.common.PeerAddress;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Client side of the tracker text protocol (see {@link TrackerServer}). */
public final class TrackerClient {
    private final String host;
    private final int port;

    public record Announcement(String infoHash, long length, int percent, String name) {
    }

    /** value = number of files (PEERS) or percent downloaded (GETPEERS) */
    public record PeerInfo(String name, PeerAddress address, int value) {
    }

    public record SearchHit(String infoHash, String name, long length, int holders, int seeders) {
    }

    public TrackerClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String address() {
        return host + ":" + port;
    }

    /** Registers this peer and the list of files it has. Sent every few seconds as a heartbeat. */
    public void announce(String peerName, String advertisedHost, int peerPort, List<Announcement> files) throws IOException {
        List<String> commands = new ArrayList<>();
        commands.add("REGISTER " + TrackerServer.encode(peerName) + " " + advertisedHost + " " + peerPort);
        for (Announcement a : files) {
            commands.add("ANNOUNCE " + advertisedHost + " " + peerPort + " " + a.infoHash() + " " + a.length() + " "
                    + a.percent() + " " + TrackerServer.encode(a.name()));
        }
        execute(commands);
    }

    public void unregister(String advertisedHost, int peerPort) throws IOException {
        execute(List.of("UNREGISTER " + advertisedHost + " " + peerPort));
    }

    public List<PeerInfo> peers() throws IOException {
        return parsePeers(execute(List.of("PEERS")));
    }

    public List<PeerInfo> peersWith(String infoHash) throws IOException {
        return parsePeers(execute(List.of("GETPEERS " + infoHash)));
    }

    public List<SearchHit> search(String keyword) throws IOException {
        List<SearchHit> hits = new ArrayList<>();
        for (String line : execute(List.of("SEARCH " + TrackerServer.encode(keyword)))) {
            String[] p = line.split(" ");
            if (p.length >= 6 && p[0].equals("FILE")) {
                hits.add(new SearchHit(p[1], TrackerServer.decode(p[5]), Long.parseLong(p[2]),
                        Integer.parseInt(p[3]), Integer.parseInt(p[4])));
            }
        }
        return hits;
    }

    private static List<PeerInfo> parsePeers(List<String> lines) {
        List<PeerInfo> result = new ArrayList<>();
        for (String line : lines) {
            String[] p = line.split(" ");
            if (p.length >= 5 && p[0].equals("PEER")) {
                result.add(new PeerInfo(TrackerServer.decode(p[1]), new PeerAddress(p[2], Integer.parseInt(p[3])),
                        Integer.parseInt(p[4])));
            }
        }
        return result;
    }

    /** Sends all commands on one connection and returns the data lines of all replies. */
    private List<String> execute(List<String> commands) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)));
            for (String command : commands) {
                out.println(command);
            }
            out.println("QUIT");
            out.flush();

            List<String> lines = new ArrayList<>();
            int pending = commands.size();
            String line;
            while (pending > 0 && (line = in.readLine()) != null) {
                if (line.equals("OK") || line.equals("END")) {
                    pending--;
                } else if (line.startsWith("ERROR")) {
                    pending--;
                    lines.add(line);
                } else {
                    lines.add(line);
                }
            }
            return lines;
        }
    }
}
