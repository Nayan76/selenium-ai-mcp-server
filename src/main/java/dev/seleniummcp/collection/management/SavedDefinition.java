package dev.seleniummcp.collection.management;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static dev.seleniummcp.collection.management.ManagementInput.*;

record SavedDefinition(String id, String name, Instant createdAt, List<Step> requests,
                       boolean stopOnFailure, int timeoutSeconds) {
    record Step(String id, String name, String route, int expectedStatus) {
        Map<String, Object> fields() {
            return Map.of("id", id, "name", name, "route", route, "expectedStatus", expectedStatus);
        }
    }

    static SavedDefinition create(Map<String, Object> args) {
        return new SavedDefinition(java.util.UUID.randomUUID().toString(), ManagementInput.name(args.get("name")), Instant.now(),
                steps(args.get("requests")), flag(args.getOrDefault("stopOnFailure", true)),
                integer(args.getOrDefault("timeoutSeconds", 60), 1, 120));
    }

    static SavedDefinition parse(Object input, String expectedId) {
        var fields = object(input, Set.of("collectionId", "name", "createdAt", "requestCount", "schemaVersion",
                "requests", "stopOnFailure", "timeoutSeconds"), Set.of());
        integer(fields.get("schemaVersion"), 1, 1);
        if (!expectedId.equals(fields.get("collectionId"))) throw invalid();
        var steps = steps(fields.get("requests"));
        if (integer(fields.get("requestCount"), 1, 20) != steps.size()) throw invalid();
        var time = Instant.parse(pattern(fields.get("createdAt"), "^[0-9TZ:.-]{20,40}$"));
        return new SavedDefinition(expectedId, ManagementInput.name(fields.get("name")), time, steps,
                flag(fields.get("stopOnFailure")), integer(fields.get("timeoutSeconds"), 1, 120));
    }

    private static List<Step> steps(Object value) {
        if (!(value instanceof List<?> rows) || rows.isEmpty() || rows.size() > 20) throw invalid();
        var ids = new HashSet<String>();
        return rows.stream().map(raw -> {
            var fields = object(raw, Set.of("id", "name", "route", "expectedStatus"), Set.of());
            String id = pattern(fields.get("id"), KEY);
            if (!ids.add(id)) throw invalid();
            return new Step(id, ManagementInput.name(fields.get("name")), pattern(fields.get("route"), KEY),
                    integer(fields.get("expectedStatus"), 100, 599));
        }).toList();
    }

    Map<String, Object> metadata() {
        return Map.of("collectionId", id, "name", name, "createdAt", createdAt.toString(), "requestCount", requests.size());
    }

    Map<String, Object> fields() {
        var fields = new java.util.LinkedHashMap<>(metadata());
        fields.put("schemaVersion", 1);
        fields.put("requests", requests.stream().map(Step::fields).toList());
        fields.put("stopOnFailure", stopOnFailure);
        fields.put("timeoutSeconds", timeoutSeconds);
        return Map.copyOf(fields);
    }
}
