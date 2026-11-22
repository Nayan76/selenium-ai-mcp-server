package dev.seleniummcp.collection;

import dev.seleniummcp.api.ApiTools;
import dev.seleniummcp.reporting.ExecutionHistory;
import dev.seleniummcp.support.ToolFailure;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

final class ApiCollectionEngine {
    private final ApiTools api;
    private final ExecutionHistory history;
    private final CollectionValidator validator;
    private final Semaphore execution = new Semaphore(1);

    ApiCollectionEngine(ApiTools api, ExecutionHistory history) {
        this.api = api;
        this.history = history;
        validator = new CollectionValidator(api);
    }

    Map<String, Object> execute(Map<String, Object> args) {
        if (!execution.tryAcquire()) throw new ToolFailure("COLLECTION_BUSY", "Another collection is executing.");
        try {
            return execute(validator.validate(args));
        } finally {
            execution.release();
        }
    }

    private Map<String, Object> execute(CollectionValidator.Plan plan) {
        String id = UUID.randomUUID().toString();
        Instant started = Instant.now();
        long start = System.nanoTime();
        long deadline = start + TimeUnit.SECONDS.toNanos(plan.timeout());
        var values = new LinkedHashMap<>(plan.variables());
        var rows = new ArrayList<Map<String, Object>>();
        int passed = 0, failed = 0, errors = 0;
        for (var request : plan.requests()) {
            var row = executeRequest(request, values, deadline);
            rows.add(row);
            switch ((String) row.get("status")) {
                case "PASS" -> passed++;
                case "FAIL" -> failed++;
                default -> errors++;
            }
            if ((plan.stop() && !"PASS".equals(row.get("status"))) || "API_RUNTIME_UNSUPPORTED".equals(row.get("code"))
                    || "COLLECTION_TIMEOUT".equals(row.get("code")) || Thread.currentThread().isInterrupted()) break;
        }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("executionId", id);
        summary.put("collectionName", plan.name());
        summary.put("startedAt", started.toString());
        summary.put("endedAt", Instant.now().toString());
        summary.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        summary.put("status", errors > 0 ? "ERROR" : failed > 0 ? "FAIL" : "PASS");
        summary.put("requestCount", plan.requests().size());
        summary.put("passed", passed);
        summary.put("failed", failed);
        summary.put("errors", errors);
        summary.put("skipped", plan.requests().size() - rows.size());
        var report = new LinkedHashMap<>(summary);
        report.put("type", "api_collection");
        report.put("requests", List.copyOf(rows));
        history.complete(Map.copyOf(report));
        return Map.copyOf(summary);
    }

    private Map<String, Object> executeRequest(CollectionValidator.Request request, Map<String, String> values,
                                               long deadline) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", request.id());
        row.put("name", request.name());
        row.put("method", request.http().get("method"));
        var assertions = new ArrayList<Map<String, Object>>();
        String status = "PASS";
        long start = System.nanoTime();
        try {
            checkDeadline(deadline);
            var response = api.sendCollectionRequest(CollectionValidator.expand(request.http(), values),
                    request.origin(), deadline);
            row.put("statusCode", response.statusCode());
            for (var rule : request.assertions()) {
                checkDeadline(deadline);
                try {
                    boolean passed = rule.status() != null ? rule.status() == response.statusCode()
                            : response.assertJson(rule.path(), rule.type(), rule.hasExpected(), rule.expected());
                    assertions.add(Map.of("type", rule.type(), "status", passed ? "PASS" : "FAIL"));
                    if (!passed && status.equals("PASS")) status = "FAIL";
                } catch (ToolFailure e) {
                    assertions.add(Map.of("type", rule.type(), "status", "ERROR", "code", e.code));
                    status = "ERROR";
                }
            }
            if (status.equals("PASS") && !request.extract().isEmpty()) {
                if (response.statusCode() < 200 || response.statusCode() > 299) {
                    throw new ToolFailure("COLLECTION_EXTRACTION_STATUS", "Extraction requires an HTTP 2xx response.");
                }
                var extracted = new LinkedHashMap<String, String>();
                request.extract().forEach((key, path) ->
                        extracted.put(key, CollectionValidator.variableValue(response.extract(path))));
                checkDeadline(deadline);
                values.putAll(extracted);
                row.put("extractedCount", extracted.size());
            }
            checkDeadline(deadline);
        } catch (ToolFailure e) {
            status = "ERROR";
            row.put("code", deadline - System.nanoTime() <= 0 && "API_TIMEOUT".equals(e.code)
                    ? "COLLECTION_TIMEOUT" : e.code);
        }
        row.put("status", status);
        row.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        row.put("assertions", List.copyOf(assertions));
        return Map.copyOf(row);
    }

    private static void checkDeadline(long deadline) {
        if (deadline - System.nanoTime() <= 0) {
            throw new ToolFailure("COLLECTION_TIMEOUT", "The collection deadline expired.");
        }
    }
}
