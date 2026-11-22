package dev.seleniummcp.engine;

import dev.seleniummcp.browser.BrowserEvidence;
import dev.seleniummcp.browser.BrowserSession;
import dev.seleniummcp.reporting.ExecutionHistory;
import dev.seleniummcp.security.Origins;
import dev.seleniummcp.support.ToolFailure;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BiConsumer;
import org.openqa.selenium.*;

public final class ScenarioEngine {
    private static final int MAX_STEPS = 32;
    private static final int MAX_SECONDS = 60;
    private static final Set<String> ACTIONS = Set.of("navigate", "click_element", "type_text", "wait_for_element");
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    static final Map<String, Object> STEP_SCHEMA = Map.of("type", "object", "additionalProperties", false,
            "required", List.of("tool", "arguments"), "properties", Map.of("tool", Map.of("type", "string"),
                    "arguments", Map.of("type", "object")));
    private static final Map<String, Object> PROPERTIES = Map.of(
            "id", Map.of("type", "string", "pattern", "^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$"),
            "description", Map.of("type", "string", "minLength", 1, "maxLength", 512),
            "steps", Map.of("type", "array", "minItems", 1, "maxItems", MAX_STEPS, "items", STEP_SCHEMA),
            "continueOnFailure", Map.of("type", "boolean", "default", false),
            "screenshotOnSuccess", Map.of("type", "boolean", "default", false),
            "collectConsole", Map.of("type", "boolean", "default", false),
            "timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", MAX_SECONDS, "default", 30));
    public static final Map<String, Object> SCHEMA = Map.of("type", "object", "additionalProperties", false,
            "required", List.of("id", "description", "steps"), "properties", PROPERTIES);

    private final BrowserSession sessions;
    private final Origins origins;
    private volatile CallToolResult last;

    private final ExecutionHistory history;
    private final BiConsumer<JsonSchema, Map<String, Object>> validator;

    public ScenarioEngine(BrowserSession sessions, Origins origins, ExecutionHistory history,
                          BiConsumer<JsonSchema, Map<String, Object>> validator) {
        this.history = history;
        this.sessions = sessions;
        this.origins = origins;
        this.validator = validator;
    }

    public static boolean supports(String name) {
        return ACTIONS.contains(name) || name.startsWith("assert_");
    }

    static void setDeadline(long deadlineNanos) {
        DEADLINE.set(deadlineNanos);
    }

    static void clearDeadline() {
        DEADLINE.remove();
    }

    static boolean deadlineExpired() {
        Long deadline = DEADLINE.get();
        return deadline != null && System.nanoTime() >= deadline;
    }

    public static Duration limitWait(Duration requested) {
        Long deadline = DEADLINE.get();
        if (deadline == null) return requested;
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new ToolFailure("SCENARIO_TIMEOUT", "Scenario deadline reached.");
        return Duration.ofNanos(Math.min(requested.toNanos(), remaining));
    }

    private record Step(String name, SyncToolSpecification tool, Map<String, Object> args) {}
    private record Scenario(String id, List<Step> steps, int seconds, boolean keepGoing) {}

    private Scenario validate(Map<?, ?> raw, Map<String, SyncToolSpecification> tools) {
        if (!PROPERTIES.keySet().containsAll(raw.keySet())
                || !(raw.get("id") instanceof String id) || !id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
                || !(raw.get("description") instanceof String description) || description.isBlank() || description.length() > 512
                || !(raw.get("steps") instanceof List<?> steps) || steps.isEmpty() || steps.size() > MAX_STEPS) {
            throw new ToolFailure("INVALID_SCENARIO", "Check the scenario ID, description, and step list (1–32).");
        }
        Object timeout = raw.containsKey("timeoutSeconds") ? raw.get("timeoutSeconds") : 30;
        Object continuation = raw.containsKey("continueOnFailure") ? raw.get("continueOnFailure") : false;
        if (!(continuation instanceof Boolean keepGoing) || !(timeout instanceof Integer || timeout instanceof Long)
                || ((Number) timeout).longValue() < 1 || ((Number) timeout).longValue() > MAX_SECONDS) {
            throw new ToolFailure("INVALID_SCENARIO", "Check continuation and scenario timeout (1–60 seconds).");
        }
        for (String option : List.of("screenshotOnSuccess", "collectConsole")) {
            if (raw.containsKey(option) && !(raw.get(option) instanceof Boolean)) {
                throw new ToolFailure("INVALID_SCENARIO", "Evidence options must be boolean.");
            }
        }
        var validated = new ArrayList<Step>();
        for (Object item : steps) {
            int index = validated.size() + 1;
            if (!(item instanceof Map<?, ?> step) || !step.keySet().equals(Set.of("tool", "arguments"))
                    || !(step.get("tool") instanceof String name) || !tools.containsKey(name)
                    || !(step.get("arguments") instanceof Map<?, ?> parameters)) {
                throw new ToolFailure("INVALID_SCENARIO", "Invalid or unsupported step " + index + ".");
            }
            var spec = tools.get(name);
            if (parameters.size() > spec.tool().inputSchema().properties().size()) {
                throw new ToolFailure("INVALID_INPUT", "Too many arguments in step " + index + ".");
            }
            Map<String, Object> args = new HashMap<>();
            for (var entry : parameters.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new ToolFailure("INVALID_SCENARIO", "Invalid argument name.");
                args.put(key, entry.getValue());
            }
            try {
                validator.accept(spec.tool().inputSchema(), args);
                if (name.equals("navigate")) origins.check((String) args.get("url"));
            } catch (ToolFailure invalid) {
                throw new ToolFailure(invalid.code, "Step " + index + ": " + invalid.getMessage());
            }
            validated.add(new Step(name, spec, Map.copyOf(args)));
        }
        return new Scenario(id, validated, ((Number) timeout).intValue(), keepGoing);
    }

    public CallToolResult execute(Map<?, ?> raw, Map<String, SyncToolSpecification> tools) {
        Scenario scenario = validate(raw, tools);
        // A scenario owns the same lock as direct driver operations for its whole run.
        synchronized (sessions) {
            long start = System.nanoTime();
            Instant started = Instant.now();
            UUID execution = UUID.randomUUID();
            var evidence = new ArrayList<Object>();
            DEADLINE.set(start + Duration.ofSeconds(scenario.seconds()).toNanos());
            try {
                var rows = new ArrayList<Map<String, Object>>();
                String overall = "PASS";
                for (Step step : scenario.steps()) {
                    long stepStart = System.nanoTime();
                    var row = new LinkedHashMap<String, Object>();
                    row.put("step", rows.size() + 1);
                    row.put("tool", step.name());
                    try {
                        limitWait(Duration.ZERO);
                        var result = sessions.use(driver -> run(driver, step));
                        limitWait(Duration.ZERO);
                        if (Boolean.TRUE.equals(result.isError())) {
                            row.put("status", "ERROR");
                            row.putAll(diagnostics(result));
                        } else if (step.name().startsWith("assert_")) {
                            var assertion = diagnostics(result);
                            if (!Set.of("PASS", "FAIL").contains(assertion.get("status"))) {
                                throw new IllegalStateException("Invalid assertion result.");
                            }
                            row.putAll(assertion);
                        } else {
                            row.put("status", "PASS");
                        }
                    } catch (ToolFailure failure) {
                        row.putAll(error(failure.code, failure.getMessage()));
                    } catch (NoSuchSessionException | NoSuchWindowException closed) {
                        row.putAll(error("BROWSER_CLOSED", "The browser or current window has closed."));
                    } catch (RuntimeException failure) {
                        var classified = ToolFailure.browser(failure);
                        row.putAll(error(classified.code, classified.getMessage()));
                    }
                    boolean expired = System.nanoTime() >= DEADLINE.get();
                    if (expired) row.putAll(error("SCENARIO_TIMEOUT", "Scenario deadline reached."));
                    row.put("durationMs", elapsed(stepStart));
                    if (!"PASS".equals(row.get("status")) || Boolean.TRUE.equals(raw.get("screenshotOnSuccess"))) {
                        BrowserEvidence.capture(sessions, origins, history, execution, row);
                        if (row.containsKey("evidence")) evidence.add(row.get("evidence"));
                    }
                    rows.add(row);
                    if (!"PASS".equals(row.get("status"))) {
                        if (!overall.equals("ERROR")) overall = (String) row.get("status");
                        if (!scenario.keepGoing() || expired) break;
                    }
                }
                var report = new LinkedHashMap<String, Object>();
                report.put("executionId", execution.toString());
                report.put("id", scenario.id());
                report.put("browser", sessions.browserType());
                report.put("startedAt", started.toString());
                report.put("status", overall);
                report.put("plannedSteps", scenario.steps().size());
                report.put("steps", rows);
                report.put("evidence", evidence);
                if (Boolean.TRUE.equals(raw.get("collectConsole"))) {
                    report.put("console", BrowserEvidence.console(sessions, origins, started));
                }
                report.put("endedAt", Instant.now().toString());
                report.put("durationMs", elapsed(start));
                history.complete(report);
                last = CallToolResult.builder().addTextContent(overall + ": " + rows.size() + " steps executed.")
                        .structuredContent(report).build();
                return last;
            } finally {
                DEADLINE.remove();
            }
        }
    }

    private CallToolResult run(WebDriver driver, Step step) {
        return runStep(driver, origins, step.name(), step.tool(), step.args());
    }

    static CallToolResult runStep(WebDriver driver, Origins origins, String name,
                                  SyncToolSpecification tool, Map<String, Object> args) {
        // A freshly launched browser can navigate away from its empty startup document.
        boolean startup = name.equals("navigate") && Set.of("about:blank", "data:,", "about:newtab")
                .contains(driver.getCurrentUrl()) && Boolean.TRUE.equals(((JavascriptExecutor) driver)
                .executeScript("return window === window.top"));
        if (!startup) origins.checkContext(driver);
        var timeouts = driver.manage().timeouts();
        Duration original = timeouts.getPageLoadTimeout();
        Duration capped = limitWait(original);
        boolean changed = capped.compareTo(original) < 0;
        if (changed) timeouts.pageLoadTimeout(Duration.ofMillis(Math.max(1, capped.toMillis())));
        try {
            var result = tool.callHandler().apply(null, new CallToolRequest(name, args));
            if (!Boolean.TRUE.equals(result.isError())) origins.checkContext(driver);
            return result;
        } finally {
            if (changed) {
                try {
                    timeouts.pageLoadTimeout(original);
                } catch (RuntimeException ignored) {
                    // Driver may have closed during the step; preserve the original step outcome.
                }
            }
        }
    }

    static Map<String, Object> diagnostics(CallToolResult result) {
        var fields = (Map<?, ?>) result.structuredContent();
        var safe = new HashMap<String, Object>();
        for (String key : List.of("code", "message", "status", "expected", "actual", "expectedFormat", "actualFormat")) {
            if (fields.containsKey(key)) safe.put(key, fields.get(key));
        }
        return safe;
    }

    static Map<String, Object> error(String code, String message) {
        return Map.of("status", "ERROR", "code", code, "message", message);
    }

    static long elapsed(long start) {
        return Duration.ofNanos(System.nanoTime() - start).toMillis();
    }

    public CallToolResult latest() {
        synchronized (sessions) {
            var result = last;
            if (result == null) throw new ToolFailure("NO_RESULTS", "No scenario has completed in this server process.");
            var record = (Map<?, ?>) result.structuredContent();
            return CallToolResult.builder().content(result.content())
                    .structuredContent(history.get((String) record.get("executionId"))).build();
        }
    }
}
