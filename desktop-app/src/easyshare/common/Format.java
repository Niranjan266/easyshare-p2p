package easyshare.common;

import java.util.BitSet;
import java.util.Locale;

/** Formatting helpers for the console and web UI. */
public final class Format {
    private Format() {
    }

    public static String bytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    public static String shortHash(String hash) {
        return hash.length() > 10 ? hash.substring(0, 10) : hash;
    }

    public static String fit(String text, int width) {
        if (text.length() <= width) {
            return String.format("%-" + width + "s", text);
        }
        return text.substring(0, width - 3) + "...";
    }

    public static String bar(int percent, int width) {
        int filled = Math.max(0, Math.min(width, percent * width / 100));
        return "#".repeat(filled) + "-".repeat(width - filled);
    }

    /** Draws which pieces are present: '#' = have, '.' = missing (grouped when there are many pieces). */
    public static String pieceMap(BitSet have, int count, int perLine) {
        int maxCells = perLine * 10;
        int cells = Math.min(count, maxCells);
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < cells; c++) {
            int from = (int) ((long) c * count / cells);
            int to = (int) ((long) (c + 1) * count / cells);
            int present = 0;
            for (int i = from; i < to; i++) {
                if (have.get(i)) {
                    present++;
                }
            }
            char symbol = present == to - from ? '#' : present == 0 ? '.' : '+';
            if (c > 0 && c % perLine == 0) {
                sb.append('\n');
            }
            sb.append(symbol);
        }
        return sb.toString();
    }
}
