package easyshare.meta;

import easyshare.common.HashUtil;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Metadata of a shared file - the EasyShare equivalent of a ".torrent" file.
 * The file is split into fixed-size pieces (chunks) and the SHA-1 hash of every piece is stored.
 * The SHA-1 of the whole metadata text is the file's "info hash", which identifies it on the network.
 *
 * <pre>
 * EASYSHARE-META/1
 * name=lecture.mp4
 * length=8388608
 * pieceLength=262144
 * fileHash=&lt;SHA-1 of the whole file&gt;
 * pieces=32
 * &lt;SHA-1 of piece 0&gt;
 * ...
 * </pre>
 */
public final class FileMeta {
    public static final String HEADER = "EASYSHARE-META/1";
    public static final String EXTENSION = ".p2pmeta";
    public static final int MIN_PIECE_LENGTH = 1024;
    public static final int MAX_PIECE_LENGTH = 8 * 1024 * 1024;
    public static final int MAX_PIECES = 200_000;

    private final String name;
    private final long length;
    private final int pieceLength;
    private final String fileHash;
    private final List<String> pieceHashes;
    private final String text;
    private final String infoHash;

    private FileMeta(String name, long length, int pieceLength, String fileHash, List<String> pieceHashes) {
        this.name = name;
        this.length = length;
        this.pieceLength = pieceLength;
        this.fileHash = fileHash;
        this.pieceHashes = List.copyOf(pieceHashes);

        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\n');
        sb.append("name=").append(name).append('\n');
        sb.append("length=").append(length).append('\n');
        sb.append("pieceLength=").append(pieceLength).append('\n');
        sb.append("fileHash=").append(fileHash).append('\n');
        sb.append("pieces=").append(pieceHashes.size()).append('\n');
        for (String h : pieceHashes) {
            sb.append(h).append('\n');
        }
        this.text = sb.toString();
        this.infoHash = HashUtil.sha1Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Reads a file piece by piece and computes all hashes (this is the "chunking" step). */
    public static FileMeta create(Path file, int pieceLength) throws IOException {
        checkPieceLength(pieceLength);
        String name = sanitizeName(file.getFileName().toString());
        if (name == null) {
            throw new IOException("Unsupported file name: " + file);
        }
        long length = Files.size(file);
        if (length <= 0) {
            throw new IOException("Empty files cannot be shared: " + file);
        }
        if ((length + pieceLength - 1) / pieceLength > MAX_PIECES) {
            throw new IOException("File too large for piece size " + pieceLength + " bytes; use a bigger --piece value");
        }
        MessageDigest whole = HashUtil.sha1();
        List<String> hashes = new ArrayList<>();
        byte[] buffer = new byte[pieceLength];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 64 * 1024)) {
            long remaining = length;
            while (remaining > 0) {
                int size = (int) Math.min(pieceLength, remaining);
                readFully(in, buffer, size);
                whole.update(buffer, 0, size);
                hashes.add(HashUtil.sha1Hex(buffer, 0, size));
                remaining -= size;
            }
        }
        return new FileMeta(name, length, pieceLength, HashUtil.toHex(whole.digest()), hashes);
    }

    /** Parses and validates metadata text received from a peer or loaded from a .p2pmeta file. */
    public static FileMeta parse(String rawText) throws IOException {
        String[] lines = rawText.replace("\r", "").split("\n");
        if (lines.length < 6 || !lines[0].equals(HEADER)) {
            throw new IOException("Not an EasyShare metadata file");
        }
        try {
            String name = value(lines[1], "name");
            long length = Long.parseLong(value(lines[2], "length"));
            int pieceLength = Integer.parseInt(value(lines[3], "pieceLength"));
            String fileHash = value(lines[4], "fileHash");
            int count = Integer.parseInt(value(lines[5], "pieces"));

            String safeName = sanitizeName(name);
            if (safeName == null || !safeName.equals(name)) {
                throw new IOException("Unsafe file name in metadata: " + name);
            }
            checkPieceLength(pieceLength);
            if (length <= 0) {
                throw new IOException("Invalid file length");
            }
            long expected = (length + pieceLength - 1) / pieceLength;
            if (count != expected || count > MAX_PIECES || lines.length < 6 + count) {
                throw new IOException("Piece list does not match the file length");
            }
            if (!HashUtil.isSha1Hex(fileHash)) {
                throw new IOException("Invalid file hash");
            }
            List<String> hashes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String h = lines[6 + i];
                if (!HashUtil.isSha1Hex(h)) {
                    throw new IOException("Invalid hash for piece " + i);
                }
                hashes.add(h);
            }
            return new FileMeta(name, length, pieceLength, fileHash, hashes);
        } catch (NumberFormatException e) {
            throw new IOException("Corrupted metadata: " + e.getMessage());
        }
    }

    public static FileMeta load(Path file) throws IOException {
        if (Files.size(file) > 32L * 1024 * 1024) {
            throw new IOException("Metadata file is too large");
        }
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public void save(Path file) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /**
     * Keeps only the last path component and replaces characters that are not allowed in file names.
     * This prevents path-traversal attacks such as a malicious peer sending "../../Windows/evil.dll".
     */
    public static String sanitizeName(String raw) {
        if (raw == null) {
            return null;
        }
        String n = raw.substring(Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1);
        StringBuilder sb = new StringBuilder();
        for (char c : n.toCharArray()) {
            sb.append(c < 32 || c == 127 || ":*?\"<>|".indexOf(c) >= 0 ? '_' : c);
        }
        n = sb.toString().trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) {
            return null;
        }
        return n;
    }

    private static void checkPieceLength(int pieceLength) throws IOException {
        if (pieceLength < MIN_PIECE_LENGTH || pieceLength > MAX_PIECE_LENGTH) {
            throw new IOException("Piece length must be between 1 KB and 8 MB");
        }
    }

    private static String value(String line, String key) throws IOException {
        if (!line.startsWith(key + "=")) {
            throw new IOException("Expected '" + key + "=' in metadata");
        }
        return line.substring(key.length() + 1);
    }

    private static void readFully(InputStream in, byte[] buffer, int size) throws IOException {
        int read = 0;
        while (read < size) {
            int n = in.read(buffer, read, size - read);
            if (n < 0) {
                throw new EOFException("File changed while hashing");
            }
            read += n;
        }
    }

    public String name() {
        return name;
    }

    public long length() {
        return length;
    }

    public int pieceLength() {
        return pieceLength;
    }

    public String fileHash() {
        return fileHash;
    }

    public String infoHash() {
        return infoHash;
    }

    public String text() {
        return text;
    }

    public int pieceCount() {
        return pieceHashes.size();
    }

    public String pieceHash(int index) {
        return pieceHashes.get(index);
    }

    public long pieceOffset(int index) {
        return (long) index * pieceLength;
    }

    public int pieceSize(int index) {
        return (int) Math.min(pieceLength, length - pieceOffset(index));
    }
}
