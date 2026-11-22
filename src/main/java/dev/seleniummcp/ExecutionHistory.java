package dev.seleniummcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Instant;
import java.util.*;
import org.slf4j.LoggerFactory;

/** Only server-generated UUIDs, numbered PNGs and fixed report formats reach the filesystem. */
final class ExecutionHistory {
    static final int MAX_IMAGE = 4 * 1024 * 1024;
    private static final int MAX_REPORT = 512 * 1024;
    private final Path base;
    private final int retention;
    private Path directory;
    private final ArtifactBudget budget;
    private final Set<String> activeEvidence = new HashSet<>();
    private final LinkedHashMap<String, Map<String, Object>> records = new LinkedHashMap<>();

    static ExecutionHistory configured() {
        int count;
        try {
            count = Integer.parseInt(System.getenv().getOrDefault("SELENIUM_HISTORY_LIMIT", "50"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("SELENIUM_HISTORY_LIMIT must be 1–200.");
        }
        long mib = setting("SELENIUM_ARTIFACT_MAX_MIB", 256, 65536);
        long hours = setting("SELENIUM_ARTIFACT_MAX_AGE_HOURS", 168, 87600);
        return new ExecutionHistory(Path.of(System.getenv().getOrDefault("SELENIUM_ARTIFACT_DIR", "selenium-artifacts")),
                count, mib * 1024 * 1024, java.time.Duration.ofHours(hours));
    }

    private static long setting(String key, int fallback, int maximum) {
        try {
            long value = Long.parseLong(System.getenv().getOrDefault(key, Integer.toString(fallback)));
            if (value >= 1 && value <= maximum) return value;
        } catch (NumberFormatException invalid) {
            // Do not include environment values in startup diagnostics.
        }
        throw new IllegalArgumentException(key + " must be between 1 and " + maximum + ".");
    }

    ExecutionHistory(Path base, int retention) {
        this(base, retention, 256L * 1024 * 1024, java.time.Duration.ofDays(7));
    }

    ExecutionHistory(Path base, int retention, long maxBytes, java.time.Duration age) {
        if (retention < 1 || retention > 200) throw new IllegalArgumentException("History limit must be 1–200.");
        this.base = base.toAbsolutePath().normalize();
        this.retention = retention;
        this.budget = new ArtifactBudget(this.base, maxBytes, age);
    }

    synchronized void complete(Map<String, Object> record) {
        String id = (String) record.get("executionId");
        activeEvidence.remove(id);
        records.put(id, record);
        if (records.size() > retention) {
            var expired = records.pollFirstEntry();
            removeArtifacts(expired.getKey());
        }
    }

    private void removeArtifacts(String id) {
        if (directory == null) return;
        try {
            budget.locked(() -> {
                budget.removeExecution(directory, id);
                return null;
            });
        } catch (IOException | SecurityException e) {
            LoggerFactory.getLogger(ExecutionHistory.class).warn("Expired execution artifact cleanup failed.");
        }
    }

    synchronized void maintain() {
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            budget.locked(() -> {
                budget.prune(directory, id -> !activeEvidence.contains(id), null, 0);
                return null;
            });
        } catch (IOException | SecurityException e) {
            LoggerFactory.getLogger(ExecutionHistory.class).warn("Artifact maintenance deferred.");
        }
    }

    synchronized void cleanup() {
        records.keySet().forEach(this::removeArtifacts);
        records.clear();
        if (directory != null) {
            try {
                checkDirectory(directory);
                Files.delete(directory);
                directory = null;
            } catch (IOException | SecurityException e) {
                LoggerFactory.getLogger(ExecutionHistory.class).warn("Session artifact directory cleanup failed.");
            }
        }
    }

    synchronized Map<String, Object> get(String id) {
        var record = records.get(id);
        if (record == null) throw new ToolFailure("EXECUTION_NOT_FOUND", "Execution is unknown or has expired.");
        if (!(record.get("evidence") instanceof List<?> evidence) || evidence.isEmpty()) return record;
        var available = new ArrayList<Object>();
        var rows = new ArrayList<Object>();
        for (Object item : (List<?>) record.get("steps")) {
            var row = (Map<?, ?>) item;
            if (row.get("evidence") instanceof Map<?, ?> ref) {
                boolean present = false;
                try {
                    checkDirectory(directory);
                    present = budget.owned(directory.resolve(id + "-" + row.get("step") + ".png"));
                } catch (IOException | SecurityException unavailable) {
                    // Fail closed when a previously captured artifact cannot be verified.
                }
                if (!present) {
                    var copy = new HashMap<Object, Object>(row);
                    copy.remove("evidence");
                    copy.put("screenshotStatus", "EXPIRED_OR_UNAVAILABLE");
                    rows.add(copy);
                    continue;
                }
                available.add(ref);
            }
            rows.add(row);
        }
        if (available.size() == evidence.size()) return record;
        var snapshot = new LinkedHashMap<>(record);
        snapshot.put("steps", rows);
        snapshot.put("evidence", available);
        return snapshot;
    }

    synchronized List<Map<String, Object>> list() {
        return records.reversed().values().stream().map(r -> Map.of(
                "executionId", r.get("executionId"), "id", r.get("id"), "status", r.get("status"),
                "endedAt", r.get("endedAt"), "durationMs", r.get("durationMs"))).toList();
    }

    synchronized Map<String, Object> screenshot(UUID id, int step, byte[] png) throws IOException {
        if (step < 1 || step > 32 || png.length > MAX_IMAGE || png.length < 8
                || !Arrays.equals(png, 0, 8, new byte[]{-119,80,78,71,13,10,26,10}, 0, 8)) {
            throw new IOException("Invalid or oversized screenshot.");
        }
        String path = save(id + "-" + step + ".png", png);
        activeEvidence.add(id.toString());
        return Map.of("executionId", id.toString(), "step", step, "timestamp", Instant.now().toString(),
                "type", "image/png", "path", path);
    }

    synchronized Map<String, Object> export(String id, String format) {
        var record = get(id);
        try {
            byte[] bytes = switch (format) {
                case "json" -> McpJsonDefaults.getMapper().writeValueAsBytes(record);
                case "html" -> HtmlReport.render(record).getBytes(StandardCharsets.UTF_8);
                default -> throw new ToolFailure("INVALID_INPUT", "Choose json or html.");
            };
            if (bytes.length > MAX_REPORT) throw new IOException("Report exceeds size limit.");
            return Map.of("executionId", id, "format", format, "path", save(id + "." + format, bytes));
        } catch (IOException | SecurityException e) {
            throw new ToolFailure("ARTIFACT_UNAVAILABLE", "Cannot safely write the report in the artifact directory.");
        }
    }

    private String save(String filename, byte[] bytes) throws IOException {
        ensureBase();
        return budget.locked(() -> install(filename, bytes));
    }

    private String install(String filename, byte[] bytes) throws IOException {
        if (directory == null) {
            directory = Files.createTempDirectory(base, ArtifactBudget.sessionPrefix(), permissions(base, "rwx------"));
        }
        checkDirectory(directory);
        Path target = directory.resolve(filename).normalize();
        if (!directory.equals(target.getParent()) || Files.isSymbolicLink(target)
                || (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Unsafe artifact target.");
        }
        String owner = filename.substring(0, 36);
        budget.prune(directory, id -> !owner.equals(id) && !activeEvidence.contains(id), target, bytes.length);
        Path stage = Files.createTempFile(directory, ".pending-", ".tmp", permissions(directory, "rw-------"));
        try {
            Files.write(stage, bytes, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            budget.install(target, bytes, stage);
        } finally {
            Files.deleteIfExists(stage);
        }
        return directory.getFileName() + "/" + filename;
    }

    private void ensureBase() throws IOException {
        Path current = base.getRoot();
        for (Path component : base) {
            Path next = current.resolve(component);
            if (!Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(next, permissions(current, "rwx------"));
                } catch (FileAlreadyExistsException race) {
                    // A concurrent process may create the same configured base.
                }
            }
            checkDirectory(next);
            current = next;
        }
    }

    static void checkDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path)) {
            throw new IOException("Artifact path must be a real directory.");
        }
    }

    static FileAttribute<?>[] permissions(Path parent, String mask) throws IOException {
        return Files.getFileStore(parent).supportsFileAttributeView(PosixFileAttributeView.class)
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mask))}
                : new FileAttribute<?>[0];
    }
}
