package easyshare.tracker;

import easyshare.common.Format;
import easyshare.common.NetUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.BindException;

/** Console front-end of the tracker. */
public final class TrackerCli {
    private TrackerCli() {
    }

    public static void run(String[] args) throws IOException, InterruptedException {
        int port = 7000;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else {
                throw new IllegalArgumentException("Unknown tracker option: " + args[i]);
            }
        }

        TrackerServer tracker = new TrackerServer(port);
        try {
            tracker.start();
        } catch (BindException e) {
            System.out.println("Port " + port + " is already in use. Is another tracker running? Try --port 7001");
            return;
        }

        System.out.println("==============================================================");
        System.out.println("  EasyShare TRACKER  (peer discovery service)");
        System.out.println("==============================================================");
        System.out.println("  Listening on port : " + port);
        System.out.println("  This PC's LAN IPs : " + String.join(", ", NetUtil.lanAddresses()));
        System.out.println("  Peers on other PCs start with:  --tracker <LAN IP>:" + port);
        System.out.println("  The tracker stores NO file data - only who has which file.");
        System.out.println("  Commands: peers | files | quit");
        System.out.println("==============================================================");

        BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = console.readLine()) != null) {
            switch (line.trim().toLowerCase()) {
                case "peers" -> {
                    System.out.println("Online peers: " + tracker.peerList().size());
                    for (TrackerServer.PeerEntry p : tracker.peerList()) {
                        System.out.println("  " + Format.fit(p.name, 16) + " " + p.host + ":" + p.port);
                    }
                }
                case "files" -> {
                    System.out.println("Files known to the tracker: " + tracker.fileList().size());
                    for (TrackerServer.FileEntry f : tracker.fileList()) {
                        long seeders = f.holders.values().stream().filter(v -> v == 100).count();
                        System.out.println("  " + Format.fit(f.name, 28) + " " + Format.fit(Format.bytes(f.length), 10)
                                + " peers: " + f.holders.size() + " (seeders: " + seeders + ")  hash " + Format.shortHash(f.infoHash));
                    }
                }
                case "quit", "exit" -> {
                    tracker.stop();
                    System.exit(0);
                }
                case "" -> {
                }
                default -> System.out.println("Commands: peers | files | quit");
            }
        }
        // console closed (e.g. started in background): keep serving
        Thread.currentThread().join();
    }
}
