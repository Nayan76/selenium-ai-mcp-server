package dev.seleniummcp.collection.management;

import dev.seleniummcp.api.ApiRuntime;
import dev.seleniummcp.api.ApiTools;
import dev.seleniummcp.collection.ApiCollectionTools.SavedStep;
import dev.seleniummcp.support.ToolFailure;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class SavedCollectionPlanner {
    private final ApiTools api;
    private final Function<String, String> environment;

    SavedCollectionPlanner(ApiTools api, Function<String, String> environment) {
        this.api = api;
        this.environment = environment;
    }

    List<SavedStep> prepare(SavedDefinition collection, EnvironmentRegistry.Environment profile) {
        ApiRuntime.requireSafeParser();
        var snapshot = new LinkedHashMap<String, String>();
        return collection.requests().stream().map(step -> prepare(step, profile, snapshot)).toList();
    }

    private SavedStep prepare(SavedDefinition.Step step, EnvironmentRegistry.Environment profile,
                              Map<String, String> snapshot) {
        var route = profile.routes().get(step.route());
        if (route == null || (!"GET".equals(route.method()) && !route.allowMutation())) {
            throw new ToolFailure("ROUTE_NOT_ALLOWED", "A saved route is unavailable or unauthorized.");
        }
        String url = profile.origin() + route.path();
        var http = new LinkedHashMap<String, Object>(Map.of("method", route.method(), "url", url));
        String origin = api.validateCollectionRequest(http);
        var headers = new LinkedHashMap<String, String>();
        route.secretHeaders().forEach((name, ref) -> {
            String value = snapshot.computeIfAbsent(ref, this::resolve);
            if (value.length() > 2048 || value.chars().anyMatch(c -> c < 32 || c > 126)) throw invalidSecret();
            headers.put(name, value);
        });
        http.put("headers", Map.copyOf(headers));
        if (route.bodyEnv() != null) {
            String body = snapshot.computeIfAbsent(route.bodyEnv(), this::resolve);
            if (body.length() > ManagementInput.MAX_BYTES || !StandardCharsets.UTF_8.newEncoder().canEncode(body)
                    || body.getBytes(StandardCharsets.UTF_8).length > ManagementInput.MAX_BYTES) throw invalidSecret();
            try {
                var json = ManagementInput.JSON.readTree(body);
                if (json == null || json.isMissingNode()) throw invalidSecret();
            } catch (tools.jackson.core.JacksonException e) {
                throw invalidSecret();
            }
            http.put("body", body);
        }
        // Full request validation runs after materialization and again at dispatch.
        try {
            api.validateCollectionRequest(http);
        } catch (ToolFailure failure) {
            if ("INVALID_INPUT".equals(failure.code)) throw invalidSecret();
            throw failure;
        }
        return new SavedStep(step.id(), step.name(), Map.copyOf(http), origin, step.expectedStatus());
    }

    private String resolve(String ref) {
        String value = environment.apply(ref);
        if (value == null || value.isBlank()) throw new ToolFailure("SECRET_UNAVAILABLE", "A required secret is unavailable.");
        return value;
    }

    private static ToolFailure invalidSecret() {
        return new ToolFailure("SECRET_INVALID", "A configured secret does not meet the request requirements.");
    }
}
