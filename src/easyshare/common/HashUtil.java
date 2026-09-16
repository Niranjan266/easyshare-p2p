package easyshare.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-1 helpers used for piece hashes, whole-file hashes and info hashes. */
public final class HashUtil {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HashUtil() {
    }

    public static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    public static String sha1Hex(byte[] data) {
        return sha1Hex(data, 0, data.length);
    }

    public static String sha1Hex(byte[] data, int offset, int length) {
        MessageDigest md = sha1();
        md.update(data, offset, length);
        return toHex(md.digest());
    }

    public static String sha1File(Path file) throws IOException {
        MessageDigest md = sha1();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                md.update(buffer, 0, n);
            }
        }
        return toHex(md.digest());
    }

    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }

    public static boolean isSha1Hex(String s) {
        return s != null && s.matches("[0-9a-f]{40}");
    }
}
