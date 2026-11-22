package dev.seleniummcp.api;

import dev.seleniummcp.support.ToolFailure;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.TokenStreamFactory;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class ApiAssertions {
    static final Set<String> TYPES = Set.of("equals", "exists", "not_exists", "contains", "type");
    static final int MAX_PATH = 256;
    private static final Set<String> JSON_TYPES = Set.of("object", "array", "string", "number", "boolean", "null");
    private static final Pattern SEGMENT = Pattern.compile("\\.([A-Za-z_][A-Za-z0-9_-]*)|\\[(0|[1-9][0-9]{0,5})\\]");
    private static final Pattern SENSITIVE = Pattern.compile(
            "token|secret|password|passwd|authorization|cookie|credential|apikey|accesskey|privatekey|sessionid|bearer");
    private static final Pattern KEY_SEPARATORS = Pattern.compile("[^a-z0-9]");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .disable(TokenStreamFactory.Feature.INTERN_PROPERTY_NAMES)
                    .disable(TokenStreamFactory.Feature.CANONICALIZE_PROPERTY_NAMES)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                            .maxTokenCount(8192).maxDocumentLength(ApiClient.MAX_RESPONSE_BYTES)
                            .maxNumberLength(128).maxNameLength(256).maxStringLength(16 * 1024).build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                    DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    record Payload(String kind, int bytes, JsonNode json) {
        static Payload read(ApiClient.Response response) {
            return read(response, true);
        }

        static Payload read(ApiClient.Response response, boolean redact) {
            byte[] body = response.body();
            if (body.length == 0) return new Payload("EMPTY", 0, null);
            if (!response.media().equals("JSON")) return new Payload(response.media(), body.length, null);
            try {
                JsonNode json = JSON.readTree(body);
                if (json == null || json.isMissingNode() || !supportedNumbers(json)) {
                    return new Payload("INVALID_JSON", body.length, null);
                }
                if (redact) removeSensitiveFields(json);
                return new Payload("JSON", body.length, json);
            } catch (JacksonException | IllegalArgumentException ignored) {
                return new Payload("INVALID_JSON", body.length, null);
            }
        }
    }

    static Map<String, Object> preview(JsonNode node, int depth) {
        String type = node.getNodeType().name().toLowerCase(Locale.ROOT);
        if (!node.isContainer()) return Map.of("type", type);
        var children = new ArrayList<Map<String, Object>>();
        if (depth > 0) {
            for (JsonNode child : node) {
                children.add(preview(child, depth - 1));
                if (children.size() == 4) break;
            }
        }
        return Map.of("type", type, "size", node.size(), "children", List.copyOf(children));
    }

    static boolean evaluate(Payload payload, Object path, String assertion, boolean hasExpected, Object expected) {
        if (!(path instanceof String expression) || expression.length() > MAX_PATH || !expression.startsWith("$")) {
            throw unsupportedPath();
        }
        boolean needsExpected = !assertion.equals("exists") && !assertion.equals("not_exists");
        if (hasExpected != needsExpected) {
            throw ApiRequestValidator.invalid("Supply expected only for equals, contains and type.");
        }
        JsonNode value = resolve(payload == null ? null : payload.json(), expression, true);
        if (payload == null) throw new ToolFailure("API_RESPONSE_UNAVAILABLE", "No API response body is available.");
        if (payload.kind().equals("INVALID_JSON")) {
            throw new ToolFailure("API_INVALID_JSON", "The response JSON is malformed or exceeds JSON parser limits.");
        }
        if (payload.json() == null) throw new ToolFailure("API_NOT_JSON", "The response has no JSON document.");
        return switch (assertion) {
            case "exists" -> value != null;
            case "not_exists" -> value == null;
            case "equals" -> equals(value, expectedJson(expected));
            case "contains" -> contains(value, expectedJson(expected));
            case "type" -> matchesType(value, expected);
            default -> throw ApiRequestValidator.invalid("Choose a supported JSON assertion type.");
        };
    }

    static void validatePath(String expression, boolean protectFields) {
        if (expression.length() > MAX_PATH || !expression.startsWith("$")) throw unsupportedPath();
        resolve(null, expression, protectFields);
    }

    static void validateExpectation(String type, boolean present, Object expected) {
        if (!TYPES.contains(type) || present != (!type.equals("exists") && !type.equals("not_exists"))) {
            throw ApiRequestValidator.invalid("Invalid JSON assertion type or expectation.");
        }
        if (type.equals("type")) matchesType(null, expected);
        if (type.equals("equals") || type.equals("contains")) rejectExpectedTemplates(expectedJson(expected));
    }

    private static void rejectExpectedTemplates(JsonNode node) {
        if ((node.isString() && node.asString().contains("{{"))
                || node.properties().stream().anyMatch(entry -> entry.getKey().contains("{{"))) {
            throw ApiRequestValidator.invalid("Assertion expectations do not support collection templates.");
        }
        for (JsonNode child : node) rejectExpectedTemplates(child);
    }

    static String extract(Payload payload, String expression) {
        if (payload.json() == null) throw new ToolFailure("API_EXTRACTION_NOT_JSON", "Extraction requires valid JSON.");
        JsonNode value = resolve(payload.json(), expression, false);
        if (value == null) throw new ToolFailure("API_EXTRACTION_MISSING", "The extraction path was not found.");
        return value.isString() ? value.asString() : JSON.writeValueAsString(value);
    }

    private static JsonNode resolve(JsonNode value, String expression, boolean protectFields) {
        var matcher = SEGMENT.matcher(expression);
        int offset = 1;
        int segments = 0;
        while (offset < expression.length()) {
            if (++segments > 24 || !matcher.region(offset, expression.length()).lookingAt()) throw unsupportedPath();
            String field = matcher.group(1);
            if (field != null && protectFields && sensitive(field)) {
                throw new ToolFailure("API_SENSITIVE_PATH", "Assertions cannot access protected JSON fields.");
            }
            if (value != null) {
                value = field == null ? value.get(Integer.parseInt(matcher.group(2))) : value.get(field);
            }
            offset = matcher.end();
        }
        return value;
    }

    private static ToolFailure unsupportedPath() {
        return new ToolFailure("API_UNSUPPORTED_PATH", "Use $ followed by dot properties or zero-based array indexes.");
    }

    private static JsonNode expectedJson(Object value) {
        try {
            byte[] bytes = JSON.writeValueAsBytes(value);
            if (bytes.length <= ApiClient.MAX_RESPONSE_BYTES) {
                JsonNode node = JSON.readTree(bytes);
                if (supportedNumbers(node)) return node;
            }
        } catch (JacksonException | IllegalArgumentException ignored) {
            // The original exception may contain the supplied value.
        }
        throw ApiRequestValidator.invalid("Supply a JSON-compatible expected value within numeric limits.");
    }

    private static boolean supportedNumbers(JsonNode node) {
        if (node.isNumber()) return Math.abs((long) node.decimalValue().scale()) <= 1024;
        for (JsonNode child : node) {
            if (!supportedNumbers(child)) return false;
        }
        return true;
    }

    private static boolean equals(JsonNode first, JsonNode second) {
        return first != null && first.equals((a, b) -> {
            if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue());
            return a.equals(b) ? 0 : 1;
        }, second);
    }

    private static boolean contains(JsonNode value, JsonNode expected) {
        if (value == null) return false;
        if (value.isString() && expected.isString()) return value.asString().contains(expected.asString());
        if (value.isArray()) {
            for (JsonNode item : value) {
                if (equals(item, expected)) return true;
            }
        }
        return false;
    }

    private static boolean matchesType(JsonNode value, Object expected) {
        if (!(expected instanceof String type) || !JSON_TYPES.contains(type)) {
            throw ApiRequestValidator.invalid("Choose object, array, string, number, boolean or null.");
        }
        return value != null && type.equals(value.getNodeType().name().toLowerCase(Locale.ROOT));
    }

    static void removeSensitiveFields(JsonNode node) {
        if (node.isObject()) node.properties().removeIf(entry -> sensitive(entry.getKey()));
        for (JsonNode child : node) removeSensitiveFields(child);
    }

    private static boolean sensitive(String key) {
        return SENSITIVE.matcher(KEY_SEPARATORS.matcher(key.toLowerCase(Locale.ROOT)).replaceAll("")).find();
    }
}
