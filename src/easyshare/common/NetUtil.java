package easyshare.common;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class NetUtil {
    private NetUtil() {
    }

    public static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isLocalAddress(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isLoopbackAddress() || address.isAnyLocalAddress()
                    || NetworkInterface.getByInetAddress(address) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** IPv4 addresses of this computer on the local network (what other PCs should use to reach us). */
    public static List<String> lanAddresses() {
        List<String> result = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLinkLocalAddress()) {
                        result.add(a.getHostAddress());
                    }
                }
            }
        } catch (IOException ignored) {
            // no interfaces available
        }
        return result;
    }

    /** Like {@link #lanAddresses()} but "ip  (adapter name)", Wi-Fi/Ethernet adapters first. */
    public static List<String> lanAddressesWithAdapter() {
        List<String> preferred = new ArrayList<>();
        List<String> other = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                String adapter = ni.getDisplayName();
                String lower = adapter.toLowerCase();
                boolean virtual = lower.contains("virtual") || lower.contains("vmware") || lower.contains("vpn")
                        || lower.contains("radmin") || lower.contains("hyper-v") || lower.contains("tap") || lower.contains("tunnel");
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLinkLocalAddress()) {
                        (virtual ? other : preferred).add(a.getHostAddress() + "  (" + adapter + ")");
                    }
                }
            }
        } catch (IOException ignored) {
            // no interfaces available
        }
        preferred.addAll(other);
        return preferred;
    }

    public static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
