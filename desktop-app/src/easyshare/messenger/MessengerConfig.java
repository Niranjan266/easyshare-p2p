package easyshare.messenger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Messenger settings (user name, group, receive folder), saved in the user's home folder. */
public final class MessengerConfig {
    public String name;
    public String group = "EasyShare";
    public Path receiveDir;
    private final Path settingsFile;

    private MessengerConfig(Path settingsFile) {
        this.settingsFile = settingsFile;
        this.name = System.getProperty("user.name", "User");
        this.receiveDir = Path.of(System.getProperty("user.home"), "Downloads", "EasyShare Received");
    }

    public static Path appDir() {
        return Path.of(System.getProperty("user.home"), ".easyshare-messenger");
    }

    public static MessengerConfig load(Path settingsFile) {
        MessengerConfig c = new MessengerConfig(settingsFile == null ? appDir().resolve("settings.properties") : settingsFile);
        if (Files.isRegularFile(c.settingsFile)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(c.settingsFile)) {
                p.load(in);
                c.name = p.getProperty("name", c.name);
                c.group = p.getProperty("group", c.group);
                c.receiveDir = Path.of(p.getProperty("receiveDir", c.receiveDir.toString()));
            } catch (IOException | RuntimeException ignored) {
                // use defaults
            }
        }
        return c;
    }

    public void save() throws IOException {
        Files.createDirectories(settingsFile.toAbsolutePath().getParent());
        Properties p = new Properties();
        p.setProperty("name", name);
        p.setProperty("group", group);
        p.setProperty("receiveDir", receiveDir.toString());
        try (OutputStream out = Files.newOutputStream(settingsFile)) {
            p.store(out, "EasyShare Messenger settings");
        }
    }

    public static String computerName() {
        String env = System.getenv("COMPUTERNAME");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "computer";
        }
    }
}
