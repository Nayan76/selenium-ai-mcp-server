package dev.seleniummcp.api;

import dev.seleniummcp.support.ToolFailure;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class ApiTools implements AutoCloseable {
    private static final String UUID_PATTERN = "^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$";
    private static final Pattern REQUEST_ID = Pattern.compile(UUID_PATTERN);
    private static final Map<String, Object> ID_SCHEMA = Map.of("type", "string", "pattern", UUID_PATTERN);
    private final ApiRequestValidator validator;
    private final ApiClient client;
    private final ApiExecutionHistory history = new ApiExecutionHistory();

    public ApiTools(String allowedOrigins, boolean allowLoopback) {
        validator = new ApiRequestValidator(allowedOrigins, allowLoopback);
        client = new ApiClient();
    }

    /** Collection calls share the request validator, client, and concurrency limit. */
    public String validateCollectionRequest(Map<String, Object> args) {
        return validator.validate(args).origin();
    }

    public CollectionResponse sendCollectionRequest(Map<String, Object> args, String authorizedOrigin, long deadline) {
        var request = validator.validate(args);
        if (!request.origin().equals(authorizedOrigin)) {
            throw new ToolFailure("API_ORIGIN_DENIED", "The expanded request changed its authorized origin.");
        }
        var response = client.send(request, deadline);
        if (response.failure() != null) throw response.failure();
        return new CollectionResponse(response.statusCode(), ApiAssertions.Payload.read(response, false));
    }

    public static void validateCollectionPath(String path, boolean extraction) {
        ApiAssertions.validatePath(path, !extraction);
    }

    public static void validateCollectionExpectation(String type, boolean present, Object value) {
        ApiAssertions.validateExpectation(type, present, value);
    }

    public static final class CollectionResponse {
        private final int statusCode;
        private final ApiAssertions.Payload payload;

        private CollectionResponse(int statusCode, ApiAssertions.Payload payload) {
            this.statusCode = statusCode;
            this.payload = payload;
        }

        public int statusCode() { return statusCode; }

        public String extract(String path) {
            ApiAssertions.validatePath(path, false);
            return ApiAssertions.extract(payload, path);
        }

        public boolean assertJson(String path, String type, boolean present, Object expected) {
            // Compare a redacted copy. Extraction can deliberately access credentials for later requests.
            var json = payload.json() == null ? null : payload.json().deepCopy();
            if (json != null) ApiAssertions.removeSensitiveFields(json);
            return ApiAssertions.evaluate(new ApiAssertions.Payload(payload.kind(), payload.bytes(), json),
                    path, type, present, expected);
        }
    }

    public List<SyncToolSpecification> specifications() {
        return List.of(
                tool("send_api_request", "Send an HTTP request to an explicitly allowed API origin.",
                        Map.of("method", Map.of("type", "string", "enum", ApiRequestValidator.METHODS.stream().sorted().toList()),
                                "url", string(1, ApiRequestValidator.MAX_URL),
                                "headers", Map.of("type", "object", "maxProperties", ApiRequestValidator.MAX_HEADERS,
                                        "propertyNames", string(1, ApiRequestValidator.MAX_HEADER_NAME),
                                        "additionalProperties", string(0, ApiRequestValidator.MAX_HEADER_VALUE)),
                                "body", string(0, ApiRequestValidator.MAX_BODY),
                                "timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 30, "default", 15)),
                        List.of("method", "url"), this::send),
                tool("assert_api_status", "Compare a recorded API response status with an expected HTTP status.",
                        Map.of("requestId", ID_SCHEMA,
                                "expectedStatus", Map.of("type", "integer", "minimum", 100, "maximum", 599)),
                        List.of("requestId", "expectedStatus"), this::status),
                tool("assert_api_response", "Assert a value using $ with dot properties and zero-based array indexes.",
                        Map.of("requestId", ID_SCHEMA, "jsonPath", string(1, ApiAssertions.MAX_PATH),
                                "assertionType", Map.of("type", "string", "enum", ApiAssertions.TYPES.stream().sorted().toList()),
                                "expected", Map.of()),
                        List.of("requestId", "jsonPath", "assertionType"), this::response),
                tool("get_api_execution_results", "Retrieve sanitized API execution metadata and assertion outcomes.",
                        Map.of("requestId", ID_SCHEMA), List.of("requestId"),
                        args -> result(history.get(id(args)).snapshot(), false, "API execution results.")));
    }

    private CallToolResult send(Map<String, Object> args) {
        var request = validator.validate(args);
        UUID id = UUID.randomUUID();
        Instant startedAt = Instant.now();
        long start = System.nanoTime();
        var response = client.send(request);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("requestId", id.toString());
        metadata.put("method", request.httpRequest().method());
        metadata.put("origin", request.origin());
        metadata.put("startedAt", startedAt.toString());
        metadata.put("completedAt", Instant.now().toString());
        metadata.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        if (response.statusCode() != null) metadata.put("statusCode", response.statusCode());
        ApiAssertions.Payload payload = null;
        if (response.failure() == null) {
            payload = ApiAssertions.Payload.read(response);
            metadata.put("status", "COMPLETED");
            var summary = new LinkedHashMap<String, Object>();
            summary.put("kind", payload.kind());
            summary.put("bytes", payload.bytes());
            if (payload.json() != null) {
                summary.put("shape", ApiAssertions.preview(payload.json(), 2));
            }
            metadata.put("response", Map.copyOf(summary));
        } else {
            metadata.putAll(errorFields(response.failure()));
        }
        history.complete(id, new ApiExecutionHistory.Entry(metadata, payload, response.statusCode()));
        return result(metadata, response.failure() != null,
                response.failure() == null ? "API response received." : response.failure().getMessage());
    }

    private CallToolResult status(Map<String, Object> args) {
        UUID id = id(args);
        int expected = ApiRequestValidator.integer(args.get("expectedStatus"), 100, 599);
        var execution = history.get(id);
        Integer actual = execution.statusCode();
        if (actual == null) {
            return assertionError(id, execution, "status",
                    new ToolFailure("API_RESPONSE_UNAVAILABLE", "No HTTP status was received."));
        }
        return assertionResult(id, execution, "status", actual == expected,
                Map.of("statusCode", actual, "expectedStatus", expected));
    }

    private CallToolResult response(Map<String, Object> args) {
        UUID id = id(args);
        if (!(args.get("assertionType") instanceof String type) || !ApiAssertions.TYPES.contains(type)) {
            throw ApiRequestValidator.invalid("Choose a supported JSON assertion type.");
        }
        var execution = history.get(id);
        try {
            boolean pass = ApiAssertions.evaluate(execution.payload(), args.get("jsonPath"), type,
                    args.containsKey("expected"), args.get("expected"));
            return assertionResult(id, execution, type, pass, Map.of());
        } catch (ToolFailure failure) {
            return assertionError(id, execution, type, failure);
        }
    }

    private static CallToolResult assertionResult(UUID id, ApiExecutionHistory.Entry execution, String type,
                                                   boolean pass, Map<String, Object> details) {
        var fields = new LinkedHashMap<>(details);
        fields.put("status", pass ? "PASS" : "FAIL");
        return recordAssertion(id, execution, type, fields, false, pass ? "API assertion passed." : "API assertion failed.");
    }

    private static CallToolResult assertionError(UUID id, ApiExecutionHistory.Entry execution, String type,
                                                  ToolFailure failure) {
        return recordAssertion(id, execution, type, errorFields(failure), true, failure.getMessage());
    }

    private static CallToolResult recordAssertion(UUID id, ApiExecutionHistory.Entry execution, String type,
                                                   Map<String, Object> fields, boolean error, String message) {
        var data = new LinkedHashMap<>(fields);
        data.put("assertionType", type);
        data.put("assertedAt", Instant.now().toString());
        execution.addAssertion(data);
        data.put("requestId", id.toString());
        return result(data, error, message);
    }

    private static UUID id(Map<String, Object> args) {
        if (!(args.get("requestId") instanceof String value) || !REQUEST_ID.matcher(value).matches()) {
            throw new ToolFailure("API_INVALID_REQUEST_ID", "Supply a lowercase API request UUID.");
        }
        return UUID.fromString(value);
    }

    private static Map<String, Object> errorFields(ToolFailure failure) {
        return Map.of("status", "ERROR", "code", failure.code, "message", failure.getMessage());
    }

    private static CallToolResult result(Map<String, Object> data, boolean error, String message) {
        return CallToolResult.builder().isError(error).addTextContent(message).structuredContent(data).build();
    }

    private static Map<String, Object> string(int min, int max) {
        return Map.of("type", "string", "minLength", min, "maxLength", max);
    }

    private static SyncToolSpecification tool(String name, String description, Map<String, Object> properties,
                                               List<String> required, Function<Map<String, Object>, CallToolResult> action) {
        var schema = new JsonSchema("object", properties, required, false, null, null);
        return SyncToolSpecification.builder().tool(Tool.builder().name(name).description(description)
                .inputSchema(schema).build()).callHandler((exchange, request) -> {
            var args = request.arguments() == null ? Map.<String, Object>of() : request.arguments();
            try {
                if (!properties.keySet().containsAll(args.keySet()) || !args.keySet().containsAll(required)) {
                    throw ApiRequestValidator.invalid("Check required and supported argument names.");
                }
                return action.apply(args);
            } catch (ToolFailure failure) {
                return result(errorFields(failure), true, failure.getMessage());
            } catch (RuntimeException ignored) {
                var failure = new ToolFailure("API_INTERNAL_ERROR", "The API operation could not be completed.");
                return result(errorFields(failure), true, failure.getMessage());
            }
        }).build();
    }

    @Override public void close() {
        client.close();
        history.close();
    }
}
