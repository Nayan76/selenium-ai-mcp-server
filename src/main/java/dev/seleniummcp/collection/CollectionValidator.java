package dev.seleniummcp.collection;

import dev.seleniummcp.api.ApiTools;
import dev.seleniummcp.support.ToolFailure;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class CollectionValidator {
    static final int MAX_REQUESTS = 20;
    static final int MAX_VARIABLES = 20;
    static final int MAX_VALUE = 4096;
    static final int MAX_ASSERTIONS = 10;
    static final int MAX_TIMEOUT = 120;
    static final String VARIABLE_NAME = "[A-Za-z_][A-Za-z0-9_]{0,63}";
    private static final Pattern TOKEN = Pattern.compile("\\{\\{(" + VARIABLE_NAME + ")}}");
    private static final Set<String> TEMPLATE_HEADERS = Set.of("authorization", "x-api-key", "x-request-id");
    private final ApiTools api;

    CollectionValidator(ApiTools api) { this.api = api; }

    record Plan(String name, List<Request> requests, Map<String, String> variables, boolean stop, int timeout) {}
    record Request(String id, String name, Map<String, Object> http, String origin,
                   List<Assertion> assertions, Map<String, String> extract) {}
    record Assertion(Integer status, String path, String type, boolean hasExpected, Object expected) {}

    Plan validate(Map<String, Object> args) {
        String name = label(args.get("collectionName"));
        Object stop = args.getOrDefault("stopOnFailure", true);
        if (!(stop instanceof Boolean failFast)) throw invalid();
        int timeout = integer(args.getOrDefault("timeoutSeconds", 60), 1, MAX_TIMEOUT);
        var initial = strings(args.getOrDefault("variables", Map.of()), MAX_VARIABLES, MAX_VALUE);
        var known = new LinkedHashMap<String, String>();
        initial.forEach((key, value) -> {
            variableName(key);
            known.put(key, variableValue(value));
        });
        var ids = new HashSet<String>();
        var requests = new ArrayList<Request>();
        int extracted = 0;
        for (Object entry : array(args.get("requests"), 1, MAX_REQUESTS)) {
            var row = object(entry, Set.of("id", "name", "method", "url", "headers", "body", "timeoutSeconds",
                    "assertions", "extract"), Set.of("id", "name", "method", "url"));
            String id = string(row.get("id"), 64);
            if (!id.matches("[A-Za-z0-9_-]{1,64}") || !ids.add(id)) throw invalid();
            String requestName = label(row.get("name"));
            var http = new LinkedHashMap<String, Object>();
            http.put("method", string(row.get("method"), 6));
            String url = string(row.get("url"), 2048);
            checkUrlTemplate(url);
            http.put("url", url);
            if (row.containsKey("body")) http.put("body", string(row.get("body"), 65536));
            if (row.containsKey("timeoutSeconds")) http.put("timeoutSeconds", integer(row.get("timeoutSeconds"), 1, 30));
            var headers = strings(optional(row, "headers", Map.of()), 32, 2048);
            for (var header : headers.entrySet()) {
                if (header.getValue().contains("{{") && !TEMPLATE_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                    throw invalid();
                }
            }
            http.put("headers", Map.copyOf(headers));
            // Render with initial values or bounded stand-ins for outputs of earlier requests.
            String origin = api.validateCollectionRequest(expand(http, known));
            var assertions = new ArrayList<Assertion>();
            for (Object assertion : array(optional(row, "assertions", List.of()), 0, MAX_ASSERTIONS)) {
                assertions.add(assertion(assertion));
            }
            var extract = strings(optional(row, "extract", Map.of()), MAX_VARIABLES, 256);
            extracted += extract.size();
            if (extracted > MAX_VARIABLES) throw invalid();
            extract.forEach((key, path) -> {
                variableName(key);
                ApiTools.validateCollectionPath(path, true);
                // Definitions cannot shadow initial values or another extraction.
                if (known.putIfAbsent(key, "x") != null) throw invalid();
            });
            requests.add(new Request(id, requestName, Map.copyOf(http), origin, List.copyOf(assertions),
                    Map.copyOf(extract)));
        }
        return new Plan(name, List.copyOf(requests), Map.copyOf(initial), failFast, timeout);
    }

    private static Assertion assertion(Object value) {
        var rule = object(value, Set.of("type", "expectedStatus", "jsonPath", "assertionType", "expected"), Set.of("type"));
        if ("status".equals(rule.get("type"))) {
            if (rule.size() != 2) throw invalid();
            return new Assertion(integer(rule.get("expectedStatus"), 100, 599), null, "status", false, null);
        }
        if (!"json".equals(rule.get("type")) || rule.containsKey("expectedStatus")) throw invalid();
        String path = string(rule.get("jsonPath"), 256);
        String type = string(rule.get("assertionType"), 12);
        ApiTools.validateCollectionPath(path, false);
        boolean present = rule.containsKey("expected");
        Object expected = rule.get("expected");
        ApiTools.validateCollectionExpectation(type, present, expected);
        return new Assertion(null, path, type, present, expected);
    }

    private static void checkUrlTemplate(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) throw invalid();
        int boundary = scheme + 3;
        while (boundary < url.length() && "/?#".indexOf(url.charAt(boundary)) < 0) boundary++;
        if (url.substring(0, boundary).indexOf('{') >= 0 || url.indexOf('#') >= 0) throw invalid();
    }

    static Map<String, Object> expand(Map<String, Object> http, Map<String, String> values) {
        var expanded = new LinkedHashMap<>(http);
        expanded.put("url", render((String) http.get("url"), values, 2048, true));
        if (http.get("body") instanceof String body) expanded.put("body", render(body, values, 65536, false));
        var headers = new LinkedHashMap<String, String>();
        ((Map<?, ?>) http.get("headers")).forEach((key, value) ->
                headers.put((String) key, render((String) value, values, 2048, false)));
        expanded.put("headers", headers);
        return expanded;
    }

    private static String render(String template, Map<String, String> values, int limit, boolean url) {
        int cursor = 0;
        var rendered = new StringBuilder();
        var matcher = TOKEN.matcher(template);
        for (int next = template.indexOf("{{"); next >= 0; next = template.indexOf("{{", cursor)) {
            if (!matcher.region(next, template.length()).lookingAt()) throw invalid();
            String value = values.get(matcher.group(1));
            if (value == null) throw new ToolFailure("COLLECTION_VARIABLE_MISSING", "A collection variable is unavailable.");
            rendered.append(template, cursor, next);
            rendered.append(url ? URLEncoder.encode(value, StandardCharsets.UTF_8)
                    .replace("+", "%20").replace("*", "%2A").replace(".", "%2E") : value);
            if (rendered.length() > limit) throw invalid();
            cursor = matcher.end();
        }
        rendered.append(template, cursor, template.length());
        if (rendered.length() > limit) throw invalid();
        return rendered.toString();
    }

    static String variableValue(String value) {
        if (value.length() > MAX_VALUE || value.contains("{{")
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE) throw invalid();
        return value;
    }

    private static String label(Object value) {
        String name = string(value, 80);
        if (name.isBlank() || name.contains("{{") || name.chars().anyMatch(Character::isISOControl)) throw invalid();
        return name;
    }

    private static void variableName(String name) {
        if (!name.matches(VARIABLE_NAME)) throw invalid();
    }

    static int integer(Object raw, int min, int max) {
        if (!(raw instanceof Integer || raw instanceof Long)) throw invalid();
        long value = ((Number) raw).longValue();
        if (value < min || value > max) throw invalid();
        return (int) value;
    }

    private static String string(Object raw, int max) {
        if (!(raw instanceof String value) || value.length() > max) throw invalid();
        return value;
    }

    private static Object optional(Map<?, ?> map, String key, Object fallback) {
        return map.containsKey(key) ? map.get(key) : fallback;
    }

    private static Map<?, ?> object(Object raw, Set<String> allowed, Set<String> required) {
        if (!(raw instanceof Map<?, ?> map) || !allowed.containsAll(map.keySet()) || !map.keySet().containsAll(required)) {
            throw invalid();
        }
        return map;
    }

    private static List<?> array(Object raw, int min, int max) {
        if (!(raw instanceof List<?> list) || list.size() < min || list.size() > max) throw invalid();
        return list;
    }

    private static Map<String, String> strings(Object raw, int count, int limit) {
        if (!(raw instanceof Map<?, ?> map) || map.size() > count) throw invalid();
        var result = new LinkedHashMap<String, String>();
        for (var entry : map.entrySet()) result.put(string(entry.getKey(), 64), string(entry.getValue(), limit));
        return result;
    }

    static ToolFailure invalid() {
        return new ToolFailure("INVALID_COLLECTION_INPUT", "Check collection fields, templates and size limits.");
    }
}
