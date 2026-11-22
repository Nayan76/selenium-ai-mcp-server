package dev.seleniummcp.collection;

import dev.seleniummcp.api.ApiTools;
import dev.seleniummcp.reporting.ExecutionHistory;
import dev.seleniummcp.support.ToolFailure;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

public final class ApiCollectionTools {
    public static final int HISTORY_LIMIT = 32;
    private static final String ID_PATTERN = "^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$";
    private static final Map<String, Object> ID = Map.of("type", "string", "pattern", ID_PATTERN);
    private static final Map<String, Object> FORMAT = choices("json", "html");
    private static final Map<String, Object> VARIABLES = Map.of("type", "object",
            "maxProperties", CollectionValidator.MAX_VARIABLES,
            "propertyNames", Map.of("pattern", "^" + CollectionValidator.VARIABLE_NAME + "$"),
            "additionalProperties", string(0, CollectionValidator.MAX_VALUE));
    private static final Map<String, Object> STATUS = object(Map.of(
            "type", choices("status"), "expectedStatus", boundedInteger(100, 599)), List.of("type", "expectedStatus"));
    private static final Map<String, Object> JSON = object(Map.of(
            "type", choices("json"), "jsonPath", string(1, 256),
            "assertionType", choices("equals", "exists", "not_exists", "contains", "type"), "expected", Map.of()),
            List.of("type", "jsonPath", "assertionType"));
    private static final Map<String, Object> REQUEST = object(Map.of(
            "id", Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{1,64}$"),
            "name", string(1, 80), "method", choices("GET", "POST", "PUT", "PATCH", "DELETE"),
            "url", string(1, 2048), "headers", stringMap(32, 2048), "body", string(0, 65536),
            "timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 30, "default", 15),
            "assertions", array(0, CollectionValidator.MAX_ASSERTIONS, Map.of("oneOf", List.of(STATUS, JSON))),
            "extract", Map.of("type", "object", "maxProperties", CollectionValidator.MAX_VARIABLES,
                    "propertyNames", Map.of("pattern", "^" + CollectionValidator.VARIABLE_NAME + "$"),
                    "additionalProperties", string(1, 256))), List.of("id", "name", "method", "url"));
    private final ExecutionHistory history;
    private final ApiCollectionEngine engine;

    public ApiCollectionTools(ApiTools api, ExecutionHistory history) {
        this.history = history;
        engine = new ApiCollectionEngine(api, history);
    }

    public record SavedStep(String id, String name, Map<String, Object> request, String origin, int expectedStatus) {}

    public Map<String, Object> executeSaved(String name, boolean stop, int timeout, Supplier<List<SavedStep>> preflight) {
        return engine.executeSaved(name, stop, timeout, preflight);
    }

    public List<SyncToolSpecification> specifications() {
        return List.of(
                tool("execute_api_collection", "Validate and run a sequential API collection.",
                        Map.of("collectionName", string(1, 80), "requests", array(1, CollectionValidator.MAX_REQUESTS, REQUEST),
                                "variables", VARIABLES, "stopOnFailure", Map.of("type", "boolean", "default", true),
                                "timeoutSeconds", Map.of("type", "integer", "minimum", 1,
                                        "maximum", CollectionValidator.MAX_TIMEOUT, "default", 60)),
                        List.of("collectionName", "requests"), engine::execute),
                tool("get_api_collection_results", "Retrieve sanitized results for a collection execution.",
                        Map.of("executionId", ID), List.of("executionId"), args -> history.get(id(args))),
                tool("list_api_collection_executions", "List recent collection summaries, newest first.",
                        Map.of("limit", Map.of("type", "integer", "minimum", 1, "maximum", HISTORY_LIMIT, "default", 10)), List.of(),
                        args -> Map.of("executions", history.collectionSummaries(
                                CollectionValidator.integer(args.getOrDefault("limit", 10), 1, HISTORY_LIMIT)))),
                tool("export_api_collection_report", "Write a sanitized JSON or HTML report and return its relative path.",
                        Map.of("executionId", ID, "format", FORMAT), List.of("executionId", "format"), args -> {
                            Object format = args.get("format");
                            if (!"json".equals(format) && !"html".equals(format)) throw CollectionValidator.invalid();
                            return history.export(id(args), (String) format);
                        }));
    }

    private static String id(Map<String, Object> args) {
        if (!(args.get("executionId") instanceof String value) || !value.matches(ID_PATTERN)) {
            throw CollectionValidator.invalid();
        }
        return value;
    }

    private static SyncToolSpecification tool(String name, String description, Map<String, Object> fields,
                                               List<String> required, Function<Map<String, Object>, Map<String, Object>> call) {
        return SyncToolSpecification.builder().tool(Tool.builder().name(name).description(description)
                .inputSchema(new JsonSchema("object", fields, required, false, null, null)).build())
                .callHandler((exchange, request) -> {
                    var args = request.arguments() == null ? Map.<String, Object>of() : request.arguments();
                    try {
                        if (!fields.keySet().containsAll(args.keySet()) || !args.keySet().containsAll(required)) {
                            throw CollectionValidator.invalid();
                        }
                        return CallToolResult.builder().addTextContent("Collection results.").structuredContent(call.apply(args)).build();
                    } catch (ToolFailure e) {
                        return error(e.code, e.getMessage());
                    } catch (RuntimeException ignored) {
                        return error("COLLECTION_ERROR", "The collection operation could not complete.");
                    }
                }).build();
    }

    private static CallToolResult error(String code, String message) {
        return CallToolResult.builder().isError(true).addTextContent(message)
                .structuredContent(Map.of("code", code, "message", message)).build();
    }

    private static Map<String, Object> object(Map<String, Object> props, List<String> required) {
        return Map.of("type", "object", "properties", props, "required", required, "additionalProperties", false);
    }
    private static Map<String, Object> array(int min, int max, Map<String, Object> items) {
        return Map.of("type", "array", "minItems", min, "maxItems", max, "items", items);
    }
    private static Map<String, Object> choices(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }
    private static Map<String, Object> string(int min, int max) {
        return Map.of("type", "string", "minLength", min, "maxLength", max);
    }
    private static Map<String, Object> boundedInteger(int min, int max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max);
    }
    private static Map<String, Object> stringMap(int count, int length) {
        return Map.of("type", "object", "maxProperties", count, "propertyNames", string(1, 64),
                "additionalProperties", string(0, length));
    }
}
