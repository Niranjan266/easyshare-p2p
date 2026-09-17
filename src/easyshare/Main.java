package easyshare;

import easyshare.peer.PeerCli;
import easyshare.peer.PeerConfig;
import easyshare.tracker.TrackerCli;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

/** Entry point: java -jar EasyShare.jar (tracker | peer | makefile | selftest) [options] */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        try {
            switch (args[0].toLowerCase()) {
                case "tracker" -> TrackerCli.run(rest);
                case "peer" -> PeerCli.run(PeerConfig.parse(rest));
                case "messenger" -> easyshare.messenger.MessengerGui.launch(rest);
                case "makefile" -> makeFile(rest);
                case "selftest" -> System.exit(SelfTest.run() ? 0 : 1);
                default -> usage();
            }
        } catch (IllegalArgumentException e) {
            System.out.println("ERROR: " + e.getMessage());
            System.out.println();
            usage();
            System.exit(2);
        }
    }

    private static void usage() {
        System.out.println("""
                EasyShare - Peer-to-Peer File Sharing System (BitTorrent-style)

                Usage:
                  java -jar EasyShare.jar tracker [--port 7000]
                  java -jar EasyShare.jar peer [options]
                  java -jar EasyShare.jar messenger [--name <name>]              IP Messenger-style window
                  java -jar EasyShare.jar makefile <path> <size in MB>     create a sample test file
                  java -jar EasyShare.jar selftest                         run automated tests

                Peer options:""");
        System.out.print(PeerConfig.OPTIONS);
        System.out.println("""

                Example:
                  java -jar EasyShare.jar peer --name Alice --port 6001 --shared shared --downloads downloads --tracker 127.0.0.1:7000 --web 8001""");
    }

    private static void makeFile(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: makefile <path> <size in MB>");
        }
        Path path = Path.of(args[0]);
        long bytes = (long) (Double.parseDouble(args[1]) * 1024 * 1024);
        if (path.toAbsolutePath().getParent() != null) {
            Files.createDirectories(path.toAbsolutePath().getParent());
        }
        Random random = new Random(path.getFileName().toString().hashCode());
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(path)) {
            long remaining = bytes;
            while (remaining > 0) {
                random.nextBytes(buffer);
                int n = (int) Math.min(buffer.length, remaining);
                out.write(buffer, 0, n);
                remaining -= n;
            }
        }
        System.out.println("Created " + path.toAbsolutePath().normalize() + " (" + bytes + " bytes)");
    }
}
