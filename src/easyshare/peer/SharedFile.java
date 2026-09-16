package easyshare.peer;

import easyshare.common.HashUtil;
import easyshare.meta.FileMeta;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;

/**
 * A file this peer can serve pieces of: either a complete file (seeding) or an unfinished download
 * (".part" file). Unfinished downloads are shared too, so peers exchange pieces while downloading
 * - that is what makes the system peer-to-peer rather than client/server.
 */
public final class SharedFile {
    private final FileMeta meta;
    private final Path finalPath;
    private final BitSet have;
    private Path path;
    private boolean complete;
    private RandomAccessFile file;

    private SharedFile(FileMeta meta, Path path, Path finalPath, BitSet have, boolean complete) {
        this.meta = meta;
        this.path = path;
        this.finalPath = finalPath;
        this.have = have;
        this.complete = complete;
    }

    public static SharedFile complete(FileMeta meta, Path path) {
        BitSet all = new BitSet(meta.pieceCount());
        all.set(0, meta.pieceCount());
        return new SharedFile(meta, path, path, all, true);
    }

    public static SharedFile partial(FileMeta meta, Path partPath, Path finalPath, BitSet have) {
        return new SharedFile(meta, partPath, finalPath, have, false);
    }

    /** Creates an empty ".part" file of the right size for a new download. */
    public static SharedFile createPartial(FileMeta meta, Path partPath, Path finalPath) throws IOException {
        Files.createDirectories(partPath.toAbsolutePath().getParent());
        try (RandomAccessFile f = new RandomAccessFile(partPath.toFile(), "rw")) {
            f.setLength(meta.length());
        }
        return partial(meta, partPath, finalPath, new BitSet(meta.pieceCount()));
    }

    /** Resume support: hashes every piece already on disk and returns the pieces that are valid. */
    public static BitSet recheck(FileMeta meta, Path partPath) throws IOException {
        BitSet valid = new BitSet(meta.pieceCount());
        try (RandomAccessFile f = new RandomAccessFile(partPath.toFile(), "rw")) {
            if (f.length() != meta.length()) {
                f.setLength(meta.length());
            }
            byte[] buffer = new byte[meta.pieceLength()];
            for (int i = 0; i < meta.pieceCount(); i++) {
                int size = meta.pieceSize(i);
                f.seek(meta.pieceOffset(i));
                f.readFully(buffer, 0, size);
                if (HashUtil.sha1Hex(buffer, 0, size).equals(meta.pieceHash(i))) {
                    valid.set(i);
                }
            }
        }
        return valid;
    }

    /** Returns the bytes of a piece, or null if this peer does not have it yet. */
    public synchronized byte[] readPiece(int index) throws IOException {
        if (index < 0 || index >= meta.pieceCount() || !have.get(index)) {
            return null;
        }
        RandomAccessFile f = open();
        byte[] data = new byte[meta.pieceSize(index)];
        f.seek(meta.pieceOffset(index));
        f.readFully(data);
        return data;
    }

    /** Writes a piece that has already been verified against its SHA-1 hash. */
    public synchronized void writePiece(int index, byte[] data) throws IOException {
        if (complete || have.get(index)) {
            return;
        }
        RandomAccessFile f = open();
        f.seek(meta.pieceOffset(index));
        f.write(data);
        have.set(index);
    }

    /** Verifies the whole-file SHA-1 and renames "name.xxxx.part" to the final file name. */
    public synchronized Path finish() throws IOException {
        if (complete) {
            return path;
        }
        if (have.cardinality() != meta.pieceCount()) {
            throw new IOException("Not all pieces have been downloaded");
        }
        closeFile();
        String actual = HashUtil.sha1File(path);
        if (!actual.equals(meta.fileHash())) {
            throw new IOException("Whole-file SHA-1 mismatch: expected " + meta.fileHash() + " but got " + actual);
        }
        Path target = uniquePath(finalPath);
        Files.move(path, target);
        path = target;
        complete = true;
        return target;
    }

    private RandomAccessFile open() throws IOException {
        if (file == null) {
            file = new RandomAccessFile(path.toFile(), complete ? "r" : "rw");
        }
        return file;
    }

    private void closeFile() throws IOException {
        if (file != null) {
            file.close();
            file = null;
        }
    }

    public synchronized void close() {
        try {
            closeFile();
        } catch (IOException ignored) {
            // closing
        }
    }

    private static Path uniquePath(Path p) {
        if (!Files.exists(p)) {
            return p;
        }
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            Path candidate = p.resolveSibling(base + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    public FileMeta meta() {
        return meta;
    }

    public synchronized Path path() {
        return path;
    }

    public synchronized boolean isComplete() {
        return complete;
    }

    public synchronized BitSet bitfield() {
        return (BitSet) have.clone();
    }

    public synchronized int haveCount() {
        return have.cardinality();
    }

    public synchronized int percent() {
        return complete ? 100 : (int) (have.cardinality() * 100L / meta.pieceCount());
    }
}
