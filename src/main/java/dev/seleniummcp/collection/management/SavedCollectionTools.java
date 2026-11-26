package dev.seleniummcp.collection.management;

import dev.seleniummcp.api.ApiTools;
import dev.seleniummcp.collection.ApiCollectionTools;
import dev.seleniummcp.security.WindowsMetadata;
import dev.seleniummcp.support.ToolFailure;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import static dev.seleniummcp.collection.management.ManagementInput.*;

public final class SavedCollectionTools {
    private static final Map<String, Object> ID = Map.of("type", "string", "pattern", UUID);
    private static final Map<String, Object> NAME = Map.of("type", "string", "minLength", 1, "maxLength", 80);
    private static final Map<String, Object> ENV = Map.of("type", "string", "enum", ENVIRONMENTS);
    private static final Map<String, Object> DATE = Map.of("type", "string", "format", "date-time");
    private static final Map<String, Object> STEP = closed(Map.of("id", key(), "name", NAME, "route", key(),
            "expectedStatus", number(100, 599)));
    private static final Map<String, Object> STEPS = array(STEP, 1, 20);
    private static final Map<String, Object> META = Map.of("collectionId", ID, "name", NAME,
            "createdAt", DATE, "requestCount", number(1, 20));
    private static final Map<String, Object> SAVED = closed(with(META, Map.of("schemaVersion", number(1, 1),
            "requests", STEPS, "stopOnFailure", Map.of("type", "boolean"), "timeoutSeconds", number(1, 120))));
    private static final Map<String, Object> RUN = closed(Map.ofEntries(Map.entry("collectionId", ID),
            Map.entry("environmentId", ENV), Map.entry("executionId", ID), Map.entry("collectionName", NAME),
            Map.entry("startedAt", DATE), Map.entry("endedAt", DATE),
            Map.entry("durationMs", Map.of("type", "integer", "minimum", 0)),
            Map.entry("status", Map.of("type", "string", "enum", List.of("PASS", "FAIL", "ERROR"))),
            Map.entry("requestCount", number(1, 20)), Map.entry("passed", number(0, 20)),
            Map.entry("failed", number(0, 20)), Map.entry("errors", number(0, 20)), Map.entry("skipped", number(0, 20))));

    private final ApiCollectionTools collections;
    private final SavedCollectionPlanner planner;
    private final EnvironmentRegistry environments;
    private final SavedCollectionStore store;

    SavedCollectionTools(ApiCollectionTools collections, SavedCollectionPlanner planner,
                          EnvironmentRegistry environments, SavedCollectionStore store) {
        this.collections = collections;
        this.planner = planner;
        this.environments = environments;
        this.store = store;
    }

    public enum ConfigurationStatus { UNCONFIGURED, READY, INVALID }

    private record Configuration(EnvironmentRegistry environments, SavedCollectionStore store) {}

    public static SavedCollectionTools configured(ApiTools api, ApiCollectionTools collections) {
        var planner = new SavedCollectionPlanner(api, System::getenv);
        try {
            var configuration = loadConfiguration(System.getenv());
            if (configuration != null) {
                try { configuration.store().recover(); }
                catch (ToolFailure failure) {
                    // A transient lock on startup must not disable the feature for the process lifetime.
                    if (!"STORE_BUSY".equals(failure.code)) throw failure;
                }
                return new SavedCollectionTools(collections, planner, configuration.environments(), configuration.store());
            }
        } catch (IOException | RuntimeException | LinkageError ignored) {
            // Fail closed without logging operator paths or configuration content.
        }
        return new SavedCollectionTools(collections, planner, null, null);
    }

    public static ConfigurationStatus checkConfiguration(Map<String, String> env) {
        if (!env.containsKey("SELENIUM_API_ENVIRONMENTS_FILE") && !env.containsKey("SELENIUM_SAVED_COLLECTION_DIR")) {
            return ConfigurationStatus.UNCONFIGURED;
        }
        try {
            if (com.sun.jna.Platform.isWindows()
                    && !WindowsMetadata.isLocal(artifactPath(env), true)) {
                return ConfigurationStatus.INVALID;
            }
            var configuration = loadConfiguration(env);
            if (configuration != null && configuration.store().hasDirectoryAccess()) return ConfigurationStatus.READY;
        } catch (IOException | RuntimeException | LinkageError ignored) {
            // Diagnostics report only the outcome of validation.
        }
        return ConfigurationStatus.INVALID;
    }

    private static Configuration loadConfiguration(Map<String, String> env) throws IOException {
        String configValue = env.get("SELENIUM_API_ENVIRONMENTS_FILE");
        String rootValue = env.get("SELENIUM_SAVED_COLLECTION_DIR");
        if (configValue == null || rootValue == null) return null;
        Path config = absolute(configValue), root = absolute(rootValue);
        Path workspace = Path.of("").toRealPath();
        Path artifacts = physicalPath(artifactPath(env));
        var files = new SecureFiles();
        EnvironmentRegistry registry;
        Path checkedRoot;
        try (var directory = files.guard(root, true)) {
            checkedRoot = root.toRealPath();
            if (overlap(checkedRoot, workspace) || overlap(checkedRoot, artifacts)) {
                throw SavedCollectionStore.unavailable();
            }
        }
        try (var parent = files.guard(config.getParent(), false)) {
            files.checkFile(config);
            Path checkedConfig = config.toRealPath();
            if (checkedConfig.startsWith(workspace) || checkedConfig.startsWith(artifacts)
                    || checkedConfig.startsWith(checkedRoot)) throw SavedCollectionStore.unavailable();
            registry = EnvironmentRegistry.parse(ManagementInput.read(checkedConfig));
        }
        return new Configuration(registry, new SavedCollectionStore(checkedRoot, files));
    }

    private static Path artifactPath(Map<String, String> env) {
        return Path.of(env.getOrDefault("SELENIUM_ARTIFACT_DIR", "selenium-artifacts")).toAbsolutePath().normalize();
    }

    public List<SyncToolSpecification> specifications() {
        return List.of(
                tool("list_api_environments", Map.of(), List.of(), closed(Map.of("environments", array(ENV, 0, 3))),
                        args -> Map.of("environments", environments.enabled())),
                tool("save_api_collection", Map.of("name", NAME, "requests", STEPS,
                                "stopOnFailure", Map.of("type", "boolean", "default", true),
                                "timeoutSeconds", with(number(1, 120), Map.of("default", 60))),
                        List.of("name", "requests"), closed(Map.of("collection", SAVED)),
                        args -> Map.of("collection", store.save(SavedDefinition.create(args)).fields())),
                tool("list_saved_api_collections", Map.of("limit", with(number(1, 32), Map.of("default", 10))),
                        List.of(), closed(Map.of("collections", array(closed(META), 0, 32))),
                        args -> Map.of("collections", store.list(integer(args.getOrDefault("limit", 10), 1, 32))
                                .stream().map(SavedDefinition::metadata).toList())),
                tool("get_saved_api_collection", Map.of("collectionId", ID), List.of("collectionId"),
                        closed(Map.of("collection", SAVED)),
                        args -> Map.of("collection", store.get(pattern(args.get("collectionId"), UUID)).fields())),
                tool("execute_saved_api_collection", Map.of("collectionId", ID, "environmentId", ENV),
                        List.of("collectionId", "environmentId"), RUN, this::execute));
    }

    private Map<String, Object> execute(Map<String, Object> args) {
        String id = pattern(args.get("collectionId"), UUID);
        if (!(args.get("environmentId") instanceof String environment) || !ENVIRONMENTS.contains(environment)) throw invalid();
        var profile = environments.select(environment);
        var record = store.get(id);
        var result = collections.executeSaved(record.name(), record.stopOnFailure(), record.timeoutSeconds(),
                () -> planner.prepare(record, profile));
        return with(result, Map.of("collectionId", id, "environmentId", environment));
    }

    private SyncToolSpecification tool(String name, Map<String, Object> fields, List<String> required,
                                       Map<String, Object> output,
                                       Function<Map<String, Object>, Map<String, Object>> operation) {
        return SyncToolSpecification.builder().tool(Tool.builder().name(name)
                .description(switch (name) {
                    case "list_api_environments" -> "List operator-enabled saved collection environments.";
                    case "save_api_collection" -> "Save an immutable status-only collection definition.";
                    case "list_saved_api_collections" -> "List saved collection metadata.";
                    case "get_saved_api_collection" -> "Read a saved collection definition.";
                    default -> "Run a saved collection against an explicitly selected environment.";
                })
                .inputSchema(new JsonSchema("object", fields, required, false, null, null)).outputSchema(output).build())
                .callHandler((exchange, request) -> {
                    var args = request.arguments() == null ? Map.<String, Object>of() : request.arguments();
                    try {
                        if (!fields.keySet().containsAll(args.keySet()) || !args.keySet().containsAll(required)) throw invalid();
                        if (store == null) throw SavedCollectionStore.unavailable();
                        return CallToolResult.builder().addTextContent("Saved collection results.")
                                .structuredContent(operation.apply(args)).build();
                    } catch (ToolFailure e) {
                        return error(e.code, e.getMessage());
                    } catch (RuntimeException | LinkageError e) {
                        return error("STORE_UNAVAILABLE", "The saved collection operation could not complete.");
                    }
                }).build();
    }

    private static CallToolResult error(String code, String message) {
        return CallToolResult.builder().isError(true).addTextContent(message)
                .structuredContent(Map.of("code", code, "message", message)).build();
    }

    private static Path absolute(String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute() || !path.equals(path.normalize()) || path.getParent() == null) {
            throw SavedCollectionStore.unavailable();
        }
        return path;
    }

    private static boolean overlap(Path first, Path second) {
        return first.startsWith(second) || second.startsWith(first);
    }

    private static Path physicalPath(Path path) throws IOException {
        Path existing = path;
        while (Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        return existing.toRealPath().resolve(existing.relativize(path));
    }

    private static Map<String, Object> key() { return Map.of("type", "string", "pattern", KEY); }

    private static Map<String, Object> number(int min, int max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max);
    }

    private static Map<String, Object> array(Map<String, Object> item, int min, int max) {
        return Map.of("type", "array", "minItems", min, "maxItems", max, "items", item);
    }

    private static Map<String, Object> closed(Map<String, Object> fields) {
        return Map.of("type", "object", "properties", fields, "required", fields.keySet().stream().sorted().toList(),
                "additionalProperties", false);
    }

    private static Map<String, Object> with(Map<String, Object> fields, Map<String, Object> more) {
        var combined = new LinkedHashMap<>(fields);
        combined.putAll(more);
        return Map.copyOf(combined);
    }
}
