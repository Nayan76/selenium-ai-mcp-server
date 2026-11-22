package dev.seleniummcp.support;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/** A receipt binds one generated path to the bytes actually installed there. All mutations use the root lock. */
public final class ArtifactBudget {
    public static final int MAX_IMAGE = 4 * 1024 * 1024;
    public static final int MAX_DATASETS = 20;
    private static final Pattern RECEIPT = Pattern.compile(
            "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?:-(?:d(?:[1-9]|1[0-9]|20)-)?(?:[1-9]|[12][0-9]|3[0-2])\\.png|\\.(?:html|json)))\\.([0-9a-f]{64})\\.receipt");
    private static final Pattern SESSION = Pattern.compile("session-v5-([0-9]+)-([0-9]+)-[0-9]+");
    private final Path base;
    private final long maxBytes;
    private final Duration maxAge;

    public ArtifactBudget(Path base, long maxBytes, Duration maxAge) {
        if (maxBytes <= 0 || maxAge.isNegative() || maxAge.isZero()) throw new IllegalArgumentException("Invalid artifact limits.");
        this.base = base;
        this.maxBytes = maxBytes;
        this.maxAge = maxAge;
    }

    public static String sessionPrefix() {
        var self = ProcessHandle.current();
        return "session-v5-" + self.pid() + "-" + self.info().startInstant().orElseThrow().toEpochMilli() + "-";
    }

    public static void checkDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || !path.toRealPath().equals(path)) {
            throw new IOException("Artifact path must be a real directory.");
        }
    }

    public static FileAttribute<?>[] permissions(Path parent, String mask) throws IOException {
        return Files.getFileStore(parent).supportsFileAttributeView(PosixFileAttributeView.class)
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mask))}
                : new FileAttribute<?>[0];
    }

    public interface Operation<T> { T run() throws IOException; }

    public <T> T locked(Operation<T> operation) throws IOException {
        checkDirectory(base);
        // Non-blocking lock avoids a hung peer delaying browser operations.
        try (var channel = FileChannel.open(base.resolve(".selenium-budget.lock"), CREATE, WRITE, NOFOLLOW_LINKS);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Artifact maintenance busy.");
            return operation.run();
        } catch (java.nio.channels.OverlappingFileLockException busy) {
            throw new IOException("Artifact maintenance busy.");
        }
    }

    private record Receipt(Path file, Path marker, String hash, long size, Instant time, boolean removable) {
        boolean matches() throws IOException {
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) > MAX_IMAGE) return false;
            try (var input = Files.newInputStream(file, READ, NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(MAX_IMAGE + 1);
                return bytes.length <= MAX_IMAGE && digest(bytes).equals(hash);
            }
        }

        boolean remove() throws IOException {
            checkDirectory(file.getParent());
            if (!matches()) return false;
            Files.delete(file);
            Files.delete(marker);
            return true;
        }
    }

    private List<Receipt> receipts(Path dir, Predicate<String> removable) throws IOException {
        checkDirectory(dir);
        var result = new ArrayList<Receipt>();
        try (var markers = Files.newDirectoryStream(dir, "*.receipt")) {
            for (Path marker : markers) {
                var m = RECEIPT.matcher(marker.getFileName().toString());
                if (!m.matches() || !Files.isRegularFile(marker, NOFOLLOW_LINKS) || Files.size(marker) != 0) continue;
                Path file = dir.resolve(m.group(1));
                if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) continue;
                var info = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
                result.add(new Receipt(file, marker, m.group(2), info.size(), info.lastModifiedTime().toInstant(),
                        removable.test(m.group(1).substring(0,36))));
            }
        }
        return result;
    }

    public void prune(Path current, Predicate<String> completed, Path protectedFile, long incoming) throws IOException {
        var all = new ArrayList<Receipt>();
        try (var dirs = Files.newDirectoryStream(base, "session-v5-*")) {
            for (var dir : dirs) {
                var name = SESSION.matcher(dir.getFileName().toString());
                if (!name.matches() || !Files.isDirectory(dir, NOFOLLOW_LINKS) || !dir.toRealPath().equals(dir)) continue;
                if (dir.equals(current)) {
                    all.addAll(receipts(dir, completed));
                } else {
                    boolean inactive;
                    try {
                        long started = Long.parseLong(name.group(2));
                        inactive = ProcessHandle.of(Long.parseLong(name.group(1))).map(p -> p.info().startInstant()
                                .map(t -> t.toEpochMilli() != started).orElse(false)).orElse(true);
                    } catch (IllegalArgumentException invalid) {
                        continue;
                    }
                    all.addAll(receipts(dir, id -> inactive));
                }
            }
        }
        long used = incoming;
        for (var entry : all) if (!entry.file.equals(protectedFile)) used = Math.addExact(used, entry.size);
        all.sort(Comparator.comparing(Receipt::time));
        Instant cutoff = Instant.now().minus(maxAge);
        for (var entry : all) {
            if (entry.removable && !entry.file.equals(protectedFile) && (entry.time.isBefore(cutoff) || used > maxBytes)) {
                if (entry.remove()) used -= entry.size;
            }
        }
        if (used > maxBytes) throw new IOException("Artifact budget exhausted.");
    }

    public void removeExecution(Path directory, String id) throws IOException {
        for (var entry : receipts(directory, id::equals)) if (entry.removable) entry.remove();
    }

    public boolean owned(Path target) throws IOException {
        for (var entry : receipts(target.getParent(), id -> false)) {
            if (entry.file.equals(target) && entry.matches()) return true;
        }
        return false;
    }

    public void install(Path target, byte[] bytes, Path stage) throws IOException {
        Path marker = target.resolveSibling(target.getFileName() + "." + digest(bytes) + ".receipt");
        Receipt previous = null;
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            // Re-export is idempotent. Never overwrite a file planted at a generated name.
            if (Files.isRegularFile(marker, NOFOLLOW_LINKS) && Files.size(marker) == 0
                    && new Receipt(target, marker, digest(bytes), 0, Instant.EPOCH, false).matches()) return;
            for (var entry : receipts(target.getParent(), id -> false)) {
                if (entry.file.equals(target) && entry.matches()) {
                    previous = entry;
                    break;
                }
            }
            if (previous == null) throw new IOException("Artifact destination already occupied.");
        }
        Files.createFile(marker, permissions(target.getParent(), "rw-------"));
        try {
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            Files.deleteIfExists(marker);
            throw failure;
        }
        if (previous != null) Files.deleteIfExists(previous.marker);
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
