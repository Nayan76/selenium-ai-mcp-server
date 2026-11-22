package dev.seleniummcp.engine;

import dev.seleniummcp.browser.BrowserEvidence;
import dev.seleniummcp.browser.BrowserSession;
import dev.seleniummcp.reporting.ExecutionHistory;
import dev.seleniummcp.security.Origins;
import dev.seleniummcp.support.ArtifactBudget;
import dev.seleniummcp.support.ToolFailure;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BiConsumer;
import org.openqa.selenium.*;

public final class DataDrivenEngine {
    static final int MAX_DATASETS = ArtifactBudget.MAX_DATASETS;
    static final int MAX_STEPS_PER_DATASET = 32;
    static final int MAX_TOTAL_STEPS = 200;
    static final int MAX_VARS_PER_DATASET = 20;
    static final int MAX_VAR_VALUE_LENGTH = 2048;
    static final int DEFAULT_TIMEOUT_SECONDS = 60;
    static final int MAX_TIMEOUT_SECONDS = 120;

    private static final String ID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$";
    private static final String VAR_NAME_PATTERN = "^[A-Za-z_][A-Za-z0-9_.-]{0,63}$";
    private static final Set<String> BLANK_STARTUP = Set.of("about:blank", "data:,", "about:newtab");
    private static final String CLEAR_STORAGE = """
            try {
              window.localStorage.clear();
              window.sessionStorage.clear();
              return window.localStorage.length === 0 && window.sessionStorage.length === 0;
            } catch (_) {
              return false;
            }
            """;

    private static final Map<String, Set<String>> SUBSTITUTABLE = Map.of(
            "navigate", Set.of("url"),
            "type_text", Set.of("text"),
            "assert_element_text", Set.of("expected"),
            "assert_element_attribute", Set.of("expected"),
            "assert_page_title", Set.of("expected"),
            "assert_current_url", Set.of("expected"),
            "assert_barcode_value", Set.of("expected"));

    public static final Map<String, Object> SCENARIO_NAME_SCHEMA = Map.of(
            "type", "string", "minLength", 1, "maxLength", 64, "pattern", ID_PATTERN);
    public static final Map<String, Object> STEPS_SCHEMA = Map.of(
            "type", "array", "minItems", 1, "maxItems", MAX_STEPS_PER_DATASET, "items", ScenarioEngine.STEP_SCHEMA);
    private static final Map<String, Object> DATASET_SCHEMA = Map.of(
            "type", "object", "additionalProperties", false,
            "required", List.of("id", "variables"),
            "properties", Map.of(
                    "id", Map.of("type", "string", "minLength", 1, "maxLength", 64, "pattern", ID_PATTERN),
                    "variables", Map.of("type", "object", "minProperties", 1, "maxProperties", MAX_VARS_PER_DATASET,
                            "additionalProperties", Map.of("type", "string", "maxLength", MAX_VAR_VALUE_LENGTH))));
    public static final Map<String, Object> DATASETS_SCHEMA = Map.of(
            "type", "array", "minItems", 1, "maxItems", MAX_DATASETS, "items", DATASET_SCHEMA);
    public static final Map<String, Object> STOP_ON_FAILURE_SCHEMA = Map.of("type", "boolean", "default", false);
    public static final Map<String, Object> TIMEOUT_SCHEMA = Map.of(
            "type", "integer", "minimum", 1, "maximum", MAX_TIMEOUT_SECONDS, "default", DEFAULT_TIMEOUT_SECONDS);
    public static final Map<String, Object> SCREENSHOT_ON_SUCCESS_SCHEMA = Map.of("type", "boolean", "default", false);
    public static final Map<String, Object> COLLECT_CONSOLE_SCHEMA = Map.of("type", "boolean", "default", false);

    private final BrowserSession sessions;
    private final Origins origins;
    private final ExecutionHistory history;
    private final BiConsumer<JsonSchema, Map<String, Object>> validator;

    public DataDrivenEngine(BrowserSession sessions, Origins origins, ExecutionHistory history,
                            BiConsumer<JsonSchema, Map<String, Object>> validator) {
        this.sessions = sessions;
        this.origins = origins;
        this.history = history;
        this.validator = validator;
    }

    private record ResolvedStep(String name, SyncToolSpecification tool, Map<String, Object> args) {}
    private record DatasetPlan(int index, String id, List<ResolvedStep> steps) {}
    private record Plan(String scenarioName, List<DatasetPlan> datasets, int stepsPerDataset,
                        boolean stopOnFailure, int timeoutSeconds, boolean screenshotOnSuccess,
                        boolean collectConsole) {}

    private Plan validate(Map<String, Object> raw, Map<String, SyncToolSpecification> tools) {
        if (!(raw.get("scenarioName") instanceof String scenarioName) || !scenarioName.matches(ID_PATTERN)) {
            throw new ToolFailure("INVALID_SCENARIO", "Supply a valid scenarioName (1–64 safe identifier characters).");
        }
        if (!(raw.get("steps") instanceof List<?> rawSteps) || rawSteps.isEmpty() || rawSteps.size() > MAX_STEPS_PER_DATASET) {
            throw new ToolFailure("INVALID_SCENARIO", "Supply between 1 and " + MAX_STEPS_PER_DATASET + " steps.");
        }
        if (!(raw.get("datasets") instanceof List<?> rawDatasets) || rawDatasets.isEmpty() || rawDatasets.size() > MAX_DATASETS) {
            throw new ToolFailure("INVALID_DATASET", "Supply between 1 and " + MAX_DATASETS + " datasets.");
        }
        if ((long) rawDatasets.size() * rawSteps.size() > MAX_TOTAL_STEPS) {
            throw new ToolFailure("INVALID_SCENARIO", "Total steps across datasets must not exceed " + MAX_TOTAL_STEPS + ".");
        }
        boolean stopOnFailure = Boolean.TRUE.equals(raw.get("stopOnFailure"));
        boolean screenshotOnSuccess = Boolean.TRUE.equals(raw.get("screenshotOnSuccess"));
        boolean collectConsole = Boolean.TRUE.equals(raw.get("collectConsole"));
        Object timeoutRaw = raw.containsKey("timeoutSeconds") ? raw.get("timeoutSeconds") : DEFAULT_TIMEOUT_SECONDS;
        int timeoutSeconds = ((Number) timeoutRaw).intValue();

        var parsedDatasets = new ArrayList<Map.Entry<String, Map<String, String>>>();
        var seenDatasetIds = new HashSet<String>();
        for (int i = 0; i < rawDatasets.size(); i++) {
            int dsNum = i + 1;
            if (!(rawDatasets.get(i) instanceof Map<?, ?> ds) || !ds.keySet().equals(Set.of("id", "variables"))) {
                throw new ToolFailure("INVALID_DATASET", "Dataset " + dsNum + " must contain only 'id' and 'variables'.");
            }
            if (!(ds.get("id") instanceof String dsId) || !dsId.matches(ID_PATTERN)) {
                throw new ToolFailure("INVALID_DATASET", "Dataset " + dsNum + " has an invalid identifier.");
            }
            if (!seenDatasetIds.add(dsId)) {
                throw new ToolFailure("DUPLICATE_DATASET_ID", "Duplicate dataset identifier: " + dsId);
            }
            if (!(ds.get("variables") instanceof Map<?, ?> rawVars) || rawVars.isEmpty()
                    || rawVars.size() > MAX_VARS_PER_DATASET) {
                throw new ToolFailure("INVALID_DATASET", "Dataset '" + dsId + "' must supply 1–" + MAX_VARS_PER_DATASET + " variables.");
            }
            var vars = new HashMap<String, String>();
            for (var entry : rawVars.entrySet()) {
                if (!(entry.getKey() instanceof String varName) || !varName.matches(VAR_NAME_PATTERN)) {
                    throw new ToolFailure("INVALID_DATASET", "Dataset '" + dsId + "' contains an invalid variable name.");
                }
                if (!(entry.getValue() instanceof String varValue) || varValue.length() > MAX_VAR_VALUE_LENGTH) {
                    throw new ToolFailure("INVALID_DATASET", "Variable '" + varName + "' in dataset '" + dsId
                            + "' must be a string up to " + MAX_VAR_VALUE_LENGTH + " characters.");
                }
                vars.put(varName, varValue);
            }
            parsedDatasets.add(Map.entry(dsId, Map.copyOf(vars)));
        }

        // Structural validation of step templates before per-dataset resolution.
        var templateSteps = new ArrayList<Map.Entry<String, Map<String, Object>>>();
        for (int i = 0; i < rawSteps.size(); i++) {
            int stepNum = i + 1;
            if (!(rawSteps.get(i) instanceof Map<?, ?> step) || !step.keySet().equals(Set.of("tool", "arguments"))) {
                throw new ToolFailure("INVALID_SCENARIO", "Invalid step structure at step " + stepNum + ".");
            }
            if (!(step.get("tool") instanceof String name)) {
                throw new ToolFailure("INVALID_SCENARIO", "Invalid tool name at step " + stepNum + ".");
            }
            if (name.contains("{{") || name.contains("}}")) {
                throw new ToolFailure("UNSUPPORTED_PLACEHOLDER", "Placeholders are not allowed in step tool names (step " + stepNum + ").");
            }
            var spec = tools.get(name);
            if (spec == null) {
                throw new ToolFailure("INVALID_SCENARIO", "Unsupported step tool at step " + stepNum + ".");
            }
            if (!(step.get("arguments") instanceof Map<?, ?> parameters)) {
                throw new ToolFailure("INVALID_SCENARIO", "Invalid arguments at step " + stepNum + ".");
            }
            if (parameters.size() > spec.tool().inputSchema().properties().size()) {
                throw new ToolFailure("INVALID_INPUT", "Too many arguments in step " + stepNum + ".");
            }
            Set<String> allowedFields = SUBSTITUTABLE.getOrDefault(name, Set.of());
            Map<String, Object> templateArgs = new HashMap<>();
            for (var entry : parameters.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new ToolFailure("INVALID_SCENARIO", "Invalid argument name in step " + stepNum + ".");
                }
                Object val = entry.getValue();
                if (val instanceof String text && !allowedFields.contains(key)
                        && (text.contains("{{") || text.contains("}}"))) {
                    throw new ToolFailure("UNSUPPORTED_PLACEHOLDER",
                            "Placeholders are not allowed in argument '" + key + "' of step " + stepNum + ".");
                }
                templateArgs.put(key, val);
            }
            templateSteps.add(Map.entry(name, templateArgs));
        }

        // Resolve and prevalidate every dataset x step before touching any browser session.
        var plans = new ArrayList<DatasetPlan>(parsedDatasets.size());
        for (int d = 0; d < parsedDatasets.size(); d++) {
            String datasetId = parsedDatasets.get(d).getKey();
            Map<String, String> variables = parsedDatasets.get(d).getValue();
            var resolvedSteps = new ArrayList<ResolvedStep>(templateSteps.size());
            for (int s = 0; s < templateSteps.size(); s++) {
                int stepNum = s + 1;
                String toolName = templateSteps.get(s).getKey();
                var spec = tools.get(toolName);
                Set<String> allowedFields = SUBSTITUTABLE.getOrDefault(toolName, Set.of());
                Map<String, Object> resolvedArgs = new HashMap<>();
                for (var entry : templateSteps.get(s).getValue().entrySet()) {
                    String key = entry.getKey();
                    Object val = entry.getValue();
                    if (val instanceof String text && allowedFields.contains(key)) {
                        resolvedArgs.put(key, resolvePlaceholders(text, variables, datasetId, stepNum));
                    } else {
                        resolvedArgs.put(key, val);
                    }
                }
                try {
                    validator.accept(spec.tool().inputSchema(), resolvedArgs);
                    if (toolName.equals("navigate")) {
                        origins.check((String) resolvedArgs.get("url"));
                    }
                } catch (ToolFailure invalid) {
                    throw new ToolFailure(invalid.code, "Dataset '" + datasetId + "', step " + stepNum + ": " + invalid.getMessage());
                }
                resolvedSteps.add(new ResolvedStep(toolName, spec, Map.copyOf(resolvedArgs)));
            }
            plans.add(new DatasetPlan(d + 1, datasetId, List.copyOf(resolvedSteps)));
        }

        return new Plan(scenarioName, List.copyOf(plans), templateSteps.size(),
                stopOnFailure, timeoutSeconds, screenshotOnSuccess, collectConsole);
    }

    private static String resolvePlaceholders(String template, Map<String, String> variables,
                                              String datasetId, int stepNum) {
        var out = new StringBuilder(template.length());
        int i = 0;
        int len = template.length();
        while (i < len) {
            if (i + 1 < len && template.charAt(i) == '{' && template.charAt(i + 1) == '{') {
                int close = template.indexOf("}}", i + 2);
                if (close == -1) {
                    throw new ToolFailure("MALFORMED_PLACEHOLDER", "Unclosed placeholder in step " + stepNum + ".");
                }
                String token = template.substring(i + 2, close);
                if (token.indexOf('{') != -1 || token.indexOf('}') != -1 || !token.matches(VAR_NAME_PATTERN)) {
                    throw new ToolFailure("MALFORMED_PLACEHOLDER", "Malformed placeholder syntax in step " + stepNum + ".");
                }
                if (!variables.containsKey(token)) {
                    throw new ToolFailure("MISSING_VARIABLE",
                            "Missing variable '" + token + "' in dataset '" + datasetId + "'.");
                }
                // Append literally without re-scanning to prevent double interpolation.
                out.append(variables.get(token));
                i = close + 2;
            } else if (i + 1 < len && template.charAt(i) == '}' && template.charAt(i + 1) == '}') {
                throw new ToolFailure("MALFORMED_PLACEHOLDER", "Unexpected closing placeholder delimiter in step " + stepNum + ".");
            } else {
                out.append(template.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    public CallToolResult execute(Map<String, Object> raw, Map<String, SyncToolSpecification> tools) {
        Plan plan = validate(raw, tools);
        synchronized (sessions) {
            long start = System.nanoTime();
            Instant started = Instant.now();
            UUID execution = UUID.randomUUID();
            var evidence = new ArrayList<Object>();
            var datasetResults = new ArrayList<Map<String, Object>>();
            boolean startsWithNavigate = plan.datasets().getFirst().steps().getFirst().name().equals("navigate");
            String initialReturnUrl = startsWithNavigate ? "about:blank" : captureInitialAllowedUrl();

            ScenarioEngine.setDeadline(start + Duration.ofSeconds(plan.timeoutSeconds()).toNanos());
            try {
                // If the browser is already active and each dataset begins with navigate, start from a clean slate.
                if (startsWithNavigate && sessions.active()) {
                    try {
                        sessions.use(driver -> {
                            resetBrowserState(driver, Set.of(), "about:blank");
                            return null;
                        });
                    } catch (RuntimeException ignored) {
                        // Let the first dataset step surface session or origin errors cleanly.
                    }
                }

                String overall = "PASS";
                int passedDatasets = 0;
                int failedDatasets = 0;
                int errorDatasets = 0;
                int totalExecutedSteps = 0;

                for (int d = 0; d < plan.datasets().size(); d++) {
                    DatasetPlan dsPlan = plan.datasets().get(d);
                    long dsStart = System.nanoTime();
                    Instant dsStarted = Instant.now();
                    var visitedOrigins = new LinkedHashSet<String>();
                    var stepRows = new ArrayList<Map<String, Object>>();
                    String dsStatus = "PASS";
                    String dsErrorCode = null;
                    int passedSteps = 0;
                    int failedSteps = 0;
                    int errorSteps = 0;
                    boolean sessionDead = false;
                    boolean noSession = false;

                    for (ResolvedStep step : dsPlan.steps()) {
                        long stepStart = System.nanoTime();
                        var row = new LinkedHashMap<String, Object>();
                        row.put("step", stepRows.size() + 1);
                        row.put("tool", step.name());
                        try {
                            ScenarioEngine.limitWait(Duration.ZERO);
                            var result = sessions.use(driver -> {
                                recordAllowedOrigin(driver, visitedOrigins);
                                var callResult = ScenarioEngine.runStep(driver, origins, step.name(), step.tool(), step.args());
                                recordAllowedOrigin(driver, visitedOrigins);
                                return callResult;
                            });
                            ScenarioEngine.limitWait(Duration.ZERO);
                            if (Boolean.TRUE.equals(result.isError())) {
                                row.put("status", "ERROR");
                                row.putAll(ScenarioEngine.diagnostics(result));
                            } else if (step.name().startsWith("assert_")) {
                                var assertion = ScenarioEngine.diagnostics(result);
                                if (!Set.of("PASS", "FAIL").contains(assertion.get("status"))) {
                                    throw new IllegalStateException("Invalid assertion result.");
                                }
                                row.putAll(assertion);
                            } else {
                                row.put("status", "PASS");
                            }
                        } catch (ToolFailure failure) {
                            row.putAll(ScenarioEngine.error(failure.code, failure.getMessage()));
                            if ("NO_SESSION".equals(failure.code)) noSession = true;
                        } catch (NoSuchSessionException closed) {
                            row.putAll(ScenarioEngine.error("BROWSER_CLOSED", "The browser session has closed."));
                            sessionDead = true;
                        } catch (NoSuchWindowException closed) {
                            row.putAll(ScenarioEngine.error("BROWSER_CLOSED", "The current browser window has closed."));
                        } catch (RuntimeException failure) {
                            var classified = ToolFailure.browser(failure);
                            row.putAll(ScenarioEngine.error(classified.code, classified.getMessage()));
                            if ("SESSION_LOST".equals(classified.code)) sessionDead = true;
                        }

                        boolean expired = ScenarioEngine.deadlineExpired();
                        if (expired && !"SCENARIO_TIMEOUT".equals(row.get("code"))) {
                            row.putAll(ScenarioEngine.error("SCENARIO_TIMEOUT", "Scenario deadline reached."));
                        }
                        row.put("durationMs", ScenarioEngine.elapsed(stepStart));

                        String stepStatus = (String) row.get("status");
                        if (!"PASS".equals(stepStatus) || plan.screenshotOnSuccess()) {
                            BrowserEvidence.capture(sessions, origins, history, execution, dsPlan.index(), dsPlan.id(), row);
                            if (row.containsKey("evidence")) evidence.add(row.get("evidence"));
                        }

                        stepRows.add(row);
                        totalExecutedSteps++;
                        switch (stepStatus) {
                            case "PASS" -> passedSteps++;
                            case "FAIL" -> failedSteps++;
                            default -> errorSteps++;
                        }

                        if (!"PASS".equals(stepStatus)) {
                            if (!"ERROR".equals(dsStatus)) dsStatus = stepStatus;
                            if (dsErrorCode == null && row.get("code") instanceof String code) dsErrorCode = code;
                            break;
                        }
                    }

                    if (sessionDead) {
                        sessions.closeQuietly();
                    }

                    var dsRow = new LinkedHashMap<String, Object>();
                    dsRow.put("datasetIndex", dsPlan.index());
                    dsRow.put("datasetId", dsPlan.id());
                    dsRow.put("status", dsStatus);
                    if (!"PASS".equals(dsStatus)) {
                        dsRow.put("failureCategory", classifyFailure(dsStatus, dsErrorCode));
                    }
                    dsRow.put("plannedSteps", dsPlan.steps().size());
                    dsRow.put("executedSteps", stepRows.size());
                    dsRow.put("passedSteps", passedSteps);
                    dsRow.put("failedSteps", failedSteps);
                    dsRow.put("errorSteps", errorSteps);
                    dsRow.put("startedAt", dsStarted.toString());
                    dsRow.put("endedAt", Instant.now().toString());
                    dsRow.put("durationMs", ScenarioEngine.elapsed(dsStart));
                    dsRow.put("steps", stepRows);
                    datasetResults.add(dsRow);

                    switch (dsStatus) {
                        case "PASS" -> passedDatasets++;
                        case "FAIL" -> {
                            failedDatasets++;
                            if (!"ERROR".equals(overall)) overall = "FAIL";
                        }
                        default -> {
                            errorDatasets++;
                            overall = "ERROR";
                        }
                    }

                    boolean hasMoreDatasets = d + 1 < plan.datasets().size();
                    boolean deadlineHit = ScenarioEngine.deadlineExpired();
                    if (deadlineHit || noSession || (plan.stopOnFailure() && !"PASS".equals(dsStatus))) {
                        cleanupAfterErrorIfNeeded(dsStatus, visitedOrigins, sessionDead || noSession);
                        break;
                    }

                    if (hasMoreDatasets) {
                        try {
                            sessions.use(driver -> {
                                resetBrowserState(driver, visitedOrigins, initialReturnUrl);
                                return null;
                            });
                        } catch (RuntimeException resetError) {
                            if (resetError instanceof NoSuchSessionException) {
                                sessions.closeQuietly();
                            }
                            DatasetPlan nextPlan = plan.datasets().get(d + 1);
                            String resetCode = resetError instanceof ToolFailure tf && "SCENARIO_TIMEOUT".equals(tf.code)
                                    ? "SCENARIO_TIMEOUT" : "DATASET_RESET_FAILED";
                            String resetMessage = "SCENARIO_TIMEOUT".equals(resetCode)
                                    ? "Scenario deadline reached during browser state reset."
                                    : "Could not guarantee browser state reset before dataset '" + nextPlan.id() + "'.";
                            Instant now = Instant.now();
                            var aborted = new LinkedHashMap<String, Object>();
                            aborted.put("datasetIndex", nextPlan.index());
                            aborted.put("datasetId", nextPlan.id());
                            aborted.put("status", "ERROR");
                            aborted.put("failureCategory", classifyFailure("ERROR", resetCode));
                            aborted.put("code", resetCode);
                            aborted.put("message", resetMessage);
                            aborted.put("plannedSteps", nextPlan.steps().size());
                            aborted.put("executedSteps", 0);
                            aborted.put("passedSteps", 0);
                            aborted.put("failedSteps", 0);
                            aborted.put("errorSteps", 0);
                            aborted.put("startedAt", now.toString());
                            aborted.put("endedAt", now.toString());
                            aborted.put("durationMs", 0L);
                            aborted.put("steps", List.of());
                            datasetResults.add(aborted);
                            errorDatasets++;
                            overall = "ERROR";
                            break;
                        }
                    } else {
                        cleanupAfterErrorIfNeeded(dsStatus, visitedOrigins, sessionDead || noSession);
                    }
                }

                int executedDatasets = datasetResults.size();
                int skippedDatasets = plan.datasets().size() - executedDatasets;
                var summary = new LinkedHashMap<String, Object>();
                summary.put("totalDatasets", plan.datasets().size());
                summary.put("executedDatasets", executedDatasets);
                summary.put("passedDatasets", passedDatasets);
                summary.put("failedDatasets", failedDatasets);
                summary.put("errorDatasets", errorDatasets);
                summary.put("skippedDatasets", skippedDatasets);

                var report = new LinkedHashMap<String, Object>();
                report.put("executionId", execution.toString());
                report.put("type", "data_driven");
                report.put("id", plan.scenarioName());
                report.put("scenarioName", plan.scenarioName());
                report.put("browser", sessions.browserType());
                report.put("stopOnFailure", plan.stopOnFailure());
                report.put("startedAt", started.toString());
                report.put("status", overall);
                report.put("plannedDatasets", plan.datasets().size());
                report.put("executedDatasets", executedDatasets);
                report.put("plannedStepsPerDataset", plan.stepsPerDataset());
                report.put("plannedTotalSteps", plan.datasets().size() * plan.stepsPerDataset());
                report.put("executedSteps", totalExecutedSteps);
                report.put("summary", summary);
                report.put("datasets", datasetResults);
                report.put("evidence", evidence);
                if (plan.collectConsole()) {
                    report.put("console", BrowserEvidence.console(sessions, origins, started));
                }
                report.put("endedAt", Instant.now().toString());
                report.put("durationMs", ScenarioEngine.elapsed(start));
                history.complete(report);

                var response = new LinkedHashMap<>(report);
                response.put("reports", history.availableReports(execution.toString()));
                return CallToolResult.builder()
                        .addTextContent(overall + ": " + executedDatasets + " of " + plan.datasets().size()
                                + " datasets executed (" + passedDatasets + " PASS, "
                                + failedDatasets + " FAIL, " + errorDatasets + " ERROR).")
                        .structuredContent(response).build();
            } finally {
                ScenarioEngine.clearDeadline();
            }
        }
    }

    public CallToolResult results(String executionId) {
        var record = history.get(executionId);
        if (!"data_driven".equals(record.get("type"))) {
            throw new ToolFailure("EXECUTION_NOT_FOUND", "Data-driven execution is unknown or has expired.");
        }
        var response = new LinkedHashMap<>(record);
        response.put("reports", history.availableReports(executionId));
        return CallToolResult.builder()
                .addTextContent("Data-driven execution " + record.get("scenarioName") + ": " + record.get("status") + ".")
                .structuredContent(response).build();
    }

    private String captureInitialAllowedUrl() {
        if (!sessions.active()) return "about:blank";
        try {
            return sessions.use(driver -> {
                String url = driver.getCurrentUrl();
                if (url == null || BLANK_STARTUP.contains(url)) return "about:blank";
                origins.check(url);
                return url;
            });
        } catch (RuntimeException ignored) {
            return "about:blank";
        }
    }

    private void recordAllowedOrigin(WebDriver driver, Set<String> visitedOrigins) {
        try {
            String url = driver.getCurrentUrl();
            if (url != null && !BLANK_STARTUP.contains(url)) {
                visitedOrigins.add(origins.originOf(url));
            }
        } catch (RuntimeException ignored) {
            // Disallowed or startup URLs are never added to visited allowed origins.
        }
    }

    private void cleanupAfterErrorIfNeeded(String status, Set<String> visitedOrigins, boolean sessionGone) {
        if (!"ERROR".equals(status) || sessionGone || !sessions.active()) return;
        try {
            sessions.use(driver -> {
                closeExtraWindows(driver);
                driver.switchTo().defaultContent();
                boolean onAllowedOrBlank = false;
                try {
                    String current = driver.getCurrentUrl();
                    if (current != null && BLANK_STARTUP.contains(current)) {
                        onAllowedOrBlank = true;
                    } else if (current != null) {
                        origins.check(current);
                        onAllowedOrBlank = true;
                    }
                } catch (RuntimeException ignored) {
                    onAllowedOrBlank = false;
                }
                if (!onAllowedOrBlank) {
                    loadWithCappedTimeout(driver, "about:blank");
                }
                return null;
            });
        } catch (NoSuchSessionException closed) {
            sessions.closeQuietly();
        } catch (RuntimeException ignored) {
            // Preserve the recorded execution outcome if best-effort post-error cleanup fails.
        }
    }

    private void resetBrowserState(WebDriver driver, Set<String> visitedOrigins, String returnUrl) {
        ScenarioEngine.limitWait(Duration.ZERO);
        closeExtraWindows(driver);
        driver.switchTo().defaultContent();

        var remainingOrigins = new LinkedHashSet<String>(visitedOrigins);
        String currentOrigin = null;
        try {
            String currentUrl = driver.getCurrentUrl();
            if (currentUrl != null && !BLANK_STARTUP.contains(currentUrl)) {
                currentOrigin = origins.originOf(currentUrl);
            }
        } catch (ToolFailure ignored) {
            // Currently on a disallowed or non-HTTP document; do not execute scripts here.
        }

        if (currentOrigin != null) {
            origins.checkContext(driver);
            clearOriginState(driver);
            remainingOrigins.remove(currentOrigin);
        }

        for (String originUrl : remainingOrigins) {
            ScenarioEngine.limitWait(Duration.ZERO);
            loadWithCappedTimeout(driver, originUrl + "/");
            origins.checkContext(driver);
            clearOriginState(driver);
        }

        ScenarioEngine.limitWait(Duration.ZERO);
        loadWithCappedTimeout(driver, returnUrl);
        if (!"about:blank".equals(returnUrl)) {
            origins.checkContext(driver);
        }
    }

    private static void closeExtraWindows(WebDriver driver) {
        Set<String> handles = driver.getWindowHandles();
        if (handles.isEmpty()) {
            throw new ToolFailure("DATASET_RESET_FAILED", "No browser window is available.");
        }
        String primary;
        try {
            primary = driver.getWindowHandle();
            if (!handles.contains(primary)) primary = handles.iterator().next();
        } catch (NoSuchWindowException gone) {
            primary = handles.iterator().next();
        }
        for (String handle : handles) {
            if (!handle.equals(primary)) {
                driver.switchTo().window(handle);
                driver.close();
            }
        }
        driver.switchTo().window(primary);
    }

    private static void clearOriginState(WebDriver driver) {
        driver.manage().deleteAllCookies();
        if (!driver.manage().getCookies().isEmpty()) {
            throw new ToolFailure("DATASET_RESET_FAILED", "Cookies could not be cleared between datasets.");
        }
        Object cleared = ((JavascriptExecutor) driver).executeScript(CLEAR_STORAGE);
        if (!Boolean.TRUE.equals(cleared)) {
            throw new ToolFailure("DATASET_RESET_FAILED", "Browser storage could not be cleared between datasets.");
        }
    }

    private static void loadWithCappedTimeout(WebDriver driver, String url) {
        var timeouts = driver.manage().timeouts();
        Duration original = timeouts.getPageLoadTimeout();
        Duration capped = ScenarioEngine.limitWait(original);
        boolean changed = capped.compareTo(original) < 0;
        if (changed) timeouts.pageLoadTimeout(Duration.ofMillis(Math.max(1, capped.toMillis())));
        try {
            driver.get(url);
        } finally {
            if (changed) {
                try {
                    timeouts.pageLoadTimeout(original);
                } catch (RuntimeException ignored) {
                    // Preserve original outcome if driver closed.
                }
            }
        }
    }

    private static String classifyFailure(String status, String code) {
        if ("FAIL".equals(status)) return "ASSERTION_FAILURE";
        if (code == null) return "EXECUTION_ERROR";
        return switch (code) {
            case "SCENARIO_TIMEOUT", "TIMEOUT" -> "TIMEOUT";
            case "ORIGIN_DENIED", "INVALID_URL" -> "ORIGIN_DENIED";
            case "DATASET_RESET_FAILED" -> "RESET_FAILURE";
            case "NO_SESSION", "BROWSER_CLOSED", "SESSION_LOST", "WINDOW_NOT_FOUND", "FRAME_NOT_FOUND" -> "BROWSER_SESSION";
            case "ELEMENT_NOT_FOUND", "ELEMENT_CLICK_INTERCEPTED", "ELEMENT_NOT_INTERACTABLE",
                    "STALE_ELEMENT", "INVALID_SELECTOR", "UNSUPPORTED_FIELD" -> "ELEMENT_ERROR";
            case "BARCODE_NOT_FOUND", "BARCODE_DECODE_FAILED", "BARCODE_UNSUPPORTED_FORMAT",
                    "BARCODE_ELEMENT_NOT_VISIBLE", "BARCODE_IMAGE_UNAVAILABLE" -> "BARCODE_ERROR";
            default -> "EXECUTION_ERROR";
        };
    }
}
