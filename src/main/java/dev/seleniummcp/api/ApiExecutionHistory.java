package dev.seleniummcp.api;

import dev.seleniummcp.support.ToolFailure;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ApiExecutionHistory {
    static final int MAX_REQUESTS = 32;
    static final int MAX_ASSERTIONS = 32;
    private static final long RETENTION = Duration.ofMinutes(15).toNanos();
    private final Map<UUID, Entry> completed = new LinkedHashMap<>();
    private boolean closed;

    synchronized void complete(UUID id, Entry entry) {
        if (closed) return;
        expire();
        completed.put(id, entry);
        if (completed.size() > MAX_REQUESTS) completed.remove(completed.keySet().iterator().next());
    }

    synchronized Entry get(UUID id) {
        expire();
        Entry entry = completed.get(id);
        if (entry == null) {
            throw new ToolFailure("API_REQUEST_NOT_FOUND", "The API request is unknown or has expired.");
        }
        return entry;
    }

    synchronized void close() {
        closed = true;
        completed.clear();
    }

    private void expire() {
        long now = System.nanoTime();
        completed.values().removeIf(entry -> now - entry.completedAt >= RETENTION);
    }

    static final class Entry {
        private final long completedAt = System.nanoTime();
        private final Map<String, Object> metadata;
        private final ApiAssertions.Payload payload;
        private final Integer statusCode;
        private final ArrayDeque<Map<String, Object>> assertions = new ArrayDeque<>();

        Entry(Map<String, Object> metadata, ApiAssertions.Payload payload, Integer statusCode) {
            this.metadata = Map.copyOf(metadata);
            this.payload = payload;
            this.statusCode = statusCode;
        }

        ApiAssertions.Payload payload() { return payload; }

        Integer statusCode() { return statusCode; }

        synchronized void addAssertion(Map<String, Object> result) {
            if (assertions.size() == MAX_ASSERTIONS) assertions.removeFirst();
            assertions.addLast(Map.copyOf(result));
        }

        synchronized Map<String, Object> snapshot() {
            var snapshot = new LinkedHashMap<>(metadata);
            snapshot.put("assertions", List.copyOf(assertions));
            return snapshot;
        }
    }
}
