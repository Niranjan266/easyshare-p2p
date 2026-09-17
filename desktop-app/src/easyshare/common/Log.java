package easyshare.common;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Very small console logger with a timestamp and a tag, e.g. [12:30:01] [UPLOAD] ... */
public final class Log {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static volatile boolean quiet = false;

    private Log() {
    }

    public static void setQuiet(boolean value) {
        quiet = value;
    }

    public static synchronized void info(String tag, String message) {
        if (quiet) {
            return;
        }
        System.out.println("[" + LocalTime.now().format(TIME) + "] [" + tag + "] " + message);
    }
}
