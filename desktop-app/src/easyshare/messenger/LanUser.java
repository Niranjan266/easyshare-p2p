package easyshare.messenger;

import easyshare.common.NetUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Another computer running EasyShare Messenger, found on the local network. */
public final class LanUser {
    public final String id;
    volatile String name;
    volatile String group;
    volatile String host;
    volatile int messagePort;
    volatile int peerPort;
    volatile long lastSeen;
    private final Set<String> addresses = new LinkedHashSet<>();

    LanUser(String id) {
        this.id = id;
    }

    public String name() {
        return name;
    }

    public String group() {
        return group;
    }

    public String host() {
        return host;
    }

    /** The best address to show and to try first: one on the same subnet as our real Wi-Fi/Ethernet adapter. */
    public synchronized String address() {
        return addresses.isEmpty() ? "?" : addresses().get(0);
    }

    synchronized boolean addAddress(String ip) {
        return addresses.add(ip);
    }

    synchronized List<String> addresses() {
        List<String> list = new ArrayList<>(addresses);
        list.sort(Comparator.comparingInt(LanUser::rank));
        return list;
    }

    private static volatile List<String> localAdapters = List.of();
    private static volatile long adaptersLoadedAt;

    private static int rank(String ip) {
        if (System.currentTimeMillis() - adaptersLoadedAt > 30_000) {
            localAdapters = NetUtil.lanAddressesWithAdapter();
            adaptersLoadedAt = System.currentTimeMillis();
        }
        List<String> local = localAdapters;
        for (int i = 0; i < local.size(); i++) {
            String own = local.get(i).substring(0, local.get(i).indexOf(' '));
            if (own.substring(0, own.lastIndexOf('.')).equals(ip.substring(0, Math.max(0, ip.lastIndexOf('.'))))) {
                return i;
            }
        }
        return ip.startsWith("127.") ? 1000 : 500;
    }

    public String label() {
        return name + " (" + host + ", " + address() + ")";
    }
}
