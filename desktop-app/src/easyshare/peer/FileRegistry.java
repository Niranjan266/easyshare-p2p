package easyshare.peer;

import easyshare.common.Format;
import easyshare.common.Log;
import easyshare.meta.FileMeta;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** All files this peer shares, indexed by info hash. */
public final class FileRegistry {
    private final Map<String, SharedFile> files = new ConcurrentHashMap<>();
    private final Map<Path, CachedMeta> hashCache = new HashMap<>();

    private record CachedMeta(long size, long modified, int pieceLength, FileMeta meta) {
    }

    public SharedFile get(String infoHash) {
        return files.get(infoHash);
    }

    public void add(SharedFile file) {
        files.put(file.meta().infoHash(), file);
    }

    public List<SharedFile> list() {
        List<SharedFile> list = new ArrayList<>(files.values());
        list.sort(Comparator.comparing((SharedFile f) -> f.meta().name().toLowerCase()).thenComparing(f -> f.meta().infoHash()));
        return list;
    }

    /** Scans the shared folder, splits each file into pieces and hashes them. Returns the number of shared files. */
    public synchronized int scanFolder(Path dir, int pieceLength) throws IOException {
        Files.createDirectories(dir);
        Path folder = dir.toAbsolutePath().normalize();
        List<Path> candidates;
        try (Stream<Path> stream = Files.list(folder)) {
            candidates = stream.filter(Files::isRegularFile).filter(p -> !ignored(p)).sorted().toList();
        }
        Set<String> found = new HashSet<>();
        for (Path p : candidates) {
            try {
                long size = Files.size(p);
                if (size == 0) {
                    continue;
                }
                long modified = Files.getLastModifiedTime(p).toMillis();
                CachedMeta cached = hashCache.get(p);
                FileMeta meta;
                if (cached != null && cached.size() == size && cached.modified() == modified && cached.pieceLength() == pieceLength) {
                    meta = cached.meta();
                } else {
                    Log.info("SHARE", "Splitting " + p.getFileName() + " (" + Format.bytes(size) + ") into pieces and hashing...");
                    meta = FileMeta.create(p, pieceLength);
                    hashCache.put(p, new CachedMeta(size, modified, pieceLength, meta));
                }
                found.add(meta.infoHash());
                files.putIfAbsent(meta.infoHash(), SharedFile.complete(meta, p));
            } catch (IOException e) {
                Log.info("SHARE", "Skipping " + p.getFileName() + ": " + e.getMessage());
            }
        }
        // forget files that were deleted or changed in the shared folder
        for (SharedFile f : list()) {
            Path parent = f.path().toAbsolutePath().normalize().getParent();
            if (f.isComplete() && folder.equals(parent) && !found.contains(f.meta().infoHash())) {
                files.remove(f.meta().infoHash());
                f.close();
            }
        }
        return found.size();
    }

    /** Loads finished and unfinished downloads (resume support). */
    public void loadDownloads(Path downloadsDir, Path stateDir) {
        if (!Files.isDirectory(stateDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(stateDir, "*" + FileMeta.EXTENSION)) {
            for (Path metaFile : stream) {
                try {
                    FileMeta meta = FileMeta.load(metaFile);
                    Path doneMarker = stateDir.resolve(meta.infoHash() + ".done");
                    Path part = partPath(downloadsDir, meta);
                    if (Files.exists(doneMarker)) {
                        String fileName = FileMeta.sanitizeName(Files.readString(doneMarker, StandardCharsets.UTF_8).trim());
                        Path finished = fileName == null ? null : downloadsDir.resolve(fileName);
                        if (finished != null && Files.isRegularFile(finished) && Files.size(finished) == meta.length()) {
                            add(SharedFile.complete(meta, finished));
                        }
                    } else if (Files.exists(part)) {
                        BitSet valid = SharedFile.recheck(meta, part);
                        add(SharedFile.partial(meta, part, downloadsDir.resolve(meta.name()), valid));
                        Log.info("RESUME", "Unfinished download " + meta.name() + ": " + valid.cardinality() + "/"
                                + meta.pieceCount() + " pieces verified on disk. Type 'resume' to continue.");
                    }
                } catch (IOException e) {
                    Log.info("RESUME", "Skipping " + metaFile.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            Log.info("RESUME", "Cannot read download state: " + e.getMessage());
        }
    }

    public static Path partPath(Path downloadsDir, FileMeta meta) {
        return downloadsDir.resolve(meta.name() + "." + meta.infoHash().substring(0, 8) + ".part");
    }

    public void closeAll() {
        files.values().forEach(SharedFile::close);
    }

    private static boolean ignored(Path p) {
        String name = p.getFileName().toString();
        return name.startsWith(".") || name.endsWith(".part") || name.endsWith(FileMeta.EXTENSION)
                || name.equalsIgnoreCase("desktop.ini") || name.equalsIgnoreCase("thumbs.db");
    }
}
