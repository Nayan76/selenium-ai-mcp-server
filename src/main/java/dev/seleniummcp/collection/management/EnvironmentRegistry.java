package dev.seleniummcp.collection.management;

import dev.seleniummcp.support.ToolFailure;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static dev.seleniummcp.collection.management.ManagementInput.*;

record EnvironmentRegistry(Map<String, Environment> profiles) {
    private static final String ENV_KEY = "^[A-Za-z_][A-Za-z0-9_]{0,127}$";
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    record Environment(boolean enabled, String origin, Map<String, Route> routes) {}
    record Route(String method, String path, boolean allowMutation, Map<String, String> secretHeaders, String bodyEnv) {}

    static EnvironmentRegistry parse(Object value) {
        var config = object(value, Set.of("schemaVersion", "profiles"), Set.of());
        integer(config.get("schemaVersion"), 1, 1);
        var profiles = new LinkedHashMap<String, Environment>();
        for (var entry : map(config.get("profiles"), 3).entrySet()) {
            if (!(entry.getKey() instanceof String id) || !ENVIRONMENTS.contains(id)) throw invalid();
            var fields = object(entry.getValue(), Set.of("origin", "routes"), Set.of("enabled"));
            String origin = pattern(fields.get("origin"), "^https?://[0-9.]+(?::[0-9]{1,5})?$");
            URI uri = URI.create(origin);
            if (uri.getHost() == null || !uri.getHost().matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")
                    || uri.getPort() == 0 || uri.getPort() > 65535) throw invalid();
            var routes = new LinkedHashMap<String, Route>();
            map(fields.get("routes"), 64).forEach((key, route) -> routes.put(pattern(key, KEY), route(route)));
            profiles.put(id, new Environment(flag(fields.getOrDefault("enabled", false)), origin, Map.copyOf(routes)));
        }
        return new EnvironmentRegistry(Map.copyOf(profiles));
    }

    private static Route route(Object value) {
        var fields = object(value, Set.of("method", "path"), Set.of("allowMutation", "secretHeaders", "jsonBodyEnv"));
        if (!(fields.get("method") instanceof String method) || !METHODS.contains(method)) throw invalid();
        String path = pattern(fields.get("path"), "^/(?:[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*/?)?$");
        for (String segment : path.split("/")) if (segment.equals(".") || segment.equals("..")) throw invalid();
        var headers = new LinkedHashMap<String, String>();
        map(fields.getOrDefault("secretHeaders", Map.of()), 2).forEach((key, ref) -> {
            String header = pattern(key, "^(?i:authorization|x-api-key)$").toLowerCase(Locale.ROOT);
            if (headers.putIfAbsent(header, pattern(ref, ENV_KEY)) != null) throw invalid();
        });
        String body = fields.containsKey("jsonBodyEnv") ? pattern(fields.get("jsonBodyEnv"), ENV_KEY) : null;
        if ("GET".equals(method) && body != null) throw invalid();
        return new Route(method, path, flag(fields.getOrDefault("allowMutation", false)), Map.copyOf(headers), body);
    }

    List<String> enabled() {
        return ENVIRONMENTS.stream().filter(id -> profiles.containsKey(id) && profiles.get(id).enabled()).toList();
    }

    Environment select(String key) {
        var profile = profiles.get(key);
        if (profile == null || !profile.enabled()) throw new ToolFailure("ENVIRONMENT_DISABLED", "The API environment is disabled.");
        return profile;
    }
}
