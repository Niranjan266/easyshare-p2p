package easyshare.peer;

import easyshare.common.PeerAddress;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracker-less peer discovery on the local network using UDP multicast.
 * A peer sends "EASYSHARE-DISCOVER id" to a multicast group; every peer listening
 * in the group answers "EASYSHARE-HERE id tcpPort name" directly to the sender.
 */
public final class LanDiscovery implements Closeable {
    public static final int PORT = 45454;
    private static final String GROUP = "239.255.42.99";

    private final String peerId;
    private final String name;
    private final int tcpPort;
    private MulticastSocket socket;
    private volatile boolean running;

    public record Found(String name, PeerAddress address) {
    }

    public LanDiscovery(String peerId, String name, int tcpPort) {
        this.peerId = peerId;
        this.name = name;
        this.tcpPort = tcpPort;
    }

    public void start() throws IOException {
        InetAddress group = InetAddress.getByName(GROUP);
        socket = new MulticastSocket(PORT);
        int joined = 0;
        for (NetworkInterface ni : multicastInterfaces()) {
            try {
                socket.joinGroup(new InetSocketAddress(group, 0), ni);
                joined++;
            } catch (IOException ignored) {
                // interface does not support it
            }
        }
        if (joined == 0) {
            socket.close();
            throw new IOException("no network interface supports multicast");
        }
        running = true;
        Thread listener = new Thread(this::listen, "lan-discovery");
        listener.setDaemon(true);
        listener.start();
    }

    private void listen() {
        byte[] buffer = new byte[512];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (!running) {
                    return;
                }
                continue;
            }
            String[] parts = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).split(" ");
            if (parts.length == 2 && parts[0].equals("EASYSHARE-DISCOVER") && !parts[1].equals(peerId)) {
                byte[] reply = ("EASYSHARE-HERE " + peerId + " " + tcpPort + " "
                        + URLEncoder.encode(name, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                try {
                    socket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    /** Sends a discovery message and collects answers for timeoutMs milliseconds. */
    public static List<Found> discover(String myId, int timeoutMs) throws IOException {
        Map<String, Found> found = new LinkedHashMap<>();
        InetAddress group = InetAddress.getByName(GROUP);
        byte[] message = ("EASYSHARE-DISCOVER " + myId).getBytes(StandardCharsets.UTF_8);
        try (MulticastSocket s = new MulticastSocket(0)) {
            s.setTimeToLive(4);
            for (NetworkInterface ni : multicastInterfaces()) {
                try {
                    s.setNetworkInterface(ni);
                    s.send(new DatagramPacket(message, message.length, group, PORT));
                } catch (IOException ignored) {
                    // try next interface
                }
            }
            s.setSoTimeout(200);
            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buffer = new byte[512];
            while (System.currentTimeMillis() < end) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    s.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String[] parts = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).split(" ");
                if (parts.length == 4 && parts[0].equals("EASYSHARE-HERE") && !parts[1].equals(myId)) {
                    try {
                        PeerAddress address = new PeerAddress(packet.getAddress().getHostAddress(), Integer.parseInt(parts[2]));
                        found.putIfAbsent(parts[1], new Found(URLDecoder.decode(parts[3], StandardCharsets.UTF_8), address));
                    } catch (NumberFormatException ignored) {
                        // malformed reply
                    }
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    private static List<NetworkInterface> multicastInterfaces() throws IOException {
        List<NetworkInterface> result = new ArrayList<>();
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (ni.isUp() && ni.supportsMulticast()) {
                result.add(ni);
            }
        }
        return result;
    }

    @Override
    public void close() {
        running = false;
        if (socket != null) {
            socket.close();
        }
    }
}
