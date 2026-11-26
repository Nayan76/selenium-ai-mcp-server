package dev.seleniummcp.engine;

import com.sun.net.httpserver.HttpServer;
import dev.seleniummcp.browser.BrowserSession;
import dev.seleniummcp.reporting.ExecutionHistory;
import dev.seleniummcp.security.Origins;
import dev.seleniummcp.tools.BrowserTools;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DataDrivenScenarioTest {
    private static final Set<String> EXISTING_THIRTY_TOOLS = Set.of(
            "launch_browser", "navigate", "click_element", "type_text", "get_page_text", "find_elements",
            "take_screenshot", "close_browser", "find_interactive_elements", "wait_for_element",
            "select_dropdown", "scroll_page", "hover_element", "switch_frame", "switch_window",
            "get_element_details", "assert_element_visible", "assert_element_text", "assert_element_attribute",
            "assert_page_title", "assert_current_url", "assert_element_count", "decode_page_barcode",
            "assert_barcode_value", "execute_test_scenario", "get_test_execution_results",
            "list_test_executions", "get_test_execution", "export_test_report", "get_execution_evidence");

    private final BrowserSession sessions = new BrowserSession();
    private BrowserTools tools = new BrowserTools(sessions, new Origins("https://allowed.example"));

    private CallToolResult call(String name, Map<String, Object> args) {
        var spec = tools.specifications().stream().filter(s -> name.equals(s.tool().name())).findFirst().orElseThrow();
        return spec.callHandler().apply(null, new CallToolRequest(name, args));
    }

    private Map<?, ?> data(String name, Map<String, Object> args) {
        var result = call(name, args);
        assertNotEquals(Boolean.TRUE, result.isError(), () -> name + " " + result.structuredContent());
        return (Map<?, ?>) result.structuredContent();
    }

    private Map<?, ?> expectError(String expectedCode, String name, Map<String, Object> args) {
        var result = call(name, args);
        assertEquals(Boolean.TRUE, result.isError(), () -> "Expected error " + expectedCode + " for " + name);
        var body = (Map<?, ?>) result.structuredContent();
        assertEquals(expectedCode, body.get("code"), body::toString);
        return body;
    }

    private static Map<String, Object> step(String tool, Map<String, Object> args) {
        return Map.of("tool", tool, "arguments", args);
    }

    private static Map<String, Object> dataset(String id, Map<String, Object> vars) {
        return Map.of("id", id, "variables", vars);
    }

    private static Map<String, Object> request(String name, List<?> steps, List<?> datasets) {
        var map = new LinkedHashMap<String, Object>();
        map.put("scenarioName", name);
        map.put("steps", steps);
        map.put("datasets", datasets);
        return map;
    }

    @Test void preservesAllThirtyExistingToolsAndRegistersThirtyTwoTotal() {
        var specs = tools.specifications();
        assertEquals(32, specs.size());
        var byName = new HashMap<String, Tool>();
        for (var spec : specs) byName.put(spec.tool().name(), spec.tool());

        for (String existing : EXISTING_THIRTY_TOOLS) {
            assertTrue(byName.containsKey(existing), () -> "Missing existing tool: " + existing);
        }
        assertTrue(byName.containsKey("execute_data_driven_scenario"));
        assertTrue(byName.containsKey("get_data_driven_results"));

        // Verify new tool schemas.
        var ddSchema = byName.get("execute_data_driven_scenario").inputSchema();
        assertEquals(List.of("scenarioName", "steps", "datasets"), ddSchema.required());
        assertEquals(Boolean.FALSE, ddSchema.additionalProperties());
        assertTrue(ddSchema.properties().containsKey("stopOnFailure"));
        assertTrue(ddSchema.properties().containsKey("timeoutSeconds"));

        var resSchema = byName.get("get_data_driven_results").inputSchema();
        assertEquals(List.of("executionId"), resSchema.required());
        assertEquals(Boolean.FALSE, resSchema.additionalProperties());

        // Verify existing tool schemas remain untouched.
        assertEquals(List.of("scenario"), byName.get("execute_test_scenario").inputSchema().required());
        assertEquals(List.of("selector", "expected"), byName.get("assert_barcode_value").inputSchema().required());
        assertEquals(List.of("css", "id", "name"),
                ((Map<?, ?>) byName.get("wait_for_element").inputSchema().properties().get("strategy")).get("enum"));
        assertEquals(30,
                ((Map<?, ?>) byName.get("wait_for_element").inputSchema().properties().get("timeoutSeconds")).get("maximum"));
    }

    @Test void rejectsMissingVariablesBeforeBrowserAccess() {
        var steps = List.of(
                step("navigate", Map.of("url", "https://allowed.example/{{path}}")),
                step("type_text", Map.of("selector", "#pwd", "text", "{{password}}")));
        var datasets = List.of(
                dataset("ds-1", Map.of("path", "login", "password", "TOP-SECRET-VALUE-1")),
                dataset("ds-2", Map.of("path", "login")));
        var err = expectError("MISSING_VARIABLE", "execute_data_driven_scenario", request("missing-var", steps, datasets));
        assertFalse(err.toString().contains("TOP-SECRET-VALUE-1"));
    }

    @Test void rejectsMalformedPlaceholdersAndUnsupportedLocations() {
        var validDatasets = List.of(dataset("ds-1", Map.of("user", "alice", "url", "https://allowed.example/ok")));
        for (String bad : List.of("{{unclosed", "stray}}", "{{}}", "{{ spaced }}", "{{{nested}}}", "{{1bad}}", "{{a/b}}")) {
            var steps = List.of(step("assert_page_title", Map.of("expected", bad)));
            expectError("MALFORMED_PLACEHOLDER", "execute_data_driven_scenario", request("malformed", steps, validDatasets));
        }
        // Unsupported substitution locations must be rejected.
        var unsupportedSteps = List.of(
                List.of(step("{{tool}}", Map.of("expected", "ok"))),
                List.of(step("click_element", Map.of("selector", "{{user}}"))),
                List.of(step("assert_element_text", Map.of("selector", "#x", "strategy", "{{user}}", "expected", "ok"))),
                List.of(step("assert_element_attribute", Map.of("selector", "#x", "attribute", "{{user}}", "expected", "ok"))),
                List.of(step("assert_barcode_value", Map.of("selector", "#x", "expected", "123", "expectedFormat", "{{user}}"))));
        for (var badSteps : unsupportedSteps) {
            expectError("UNSUPPORTED_PLACEHOLDER", "execute_data_driven_scenario",
                    request("bad-loc", badSteps, validDatasets));
        }
    }

    @Test void rejectsDuplicateDatasetIdentifiers() {
        var steps = List.of(step("assert_page_title", Map.of("expected", "{{title}}")));
        var datasets = List.of(
                dataset("same-id", Map.of("title", "A")),
                dataset("same-id", Map.of("title", "B")));
        expectError("DUPLICATE_DATASET_ID", "execute_data_driven_scenario", request("dup-id", steps, datasets));
    }

    @Test void rejectsEmptyDatasetsAndEmptyVariableMaps() {
        var steps = List.of(step("assert_page_title", Map.of("expected", "Fixed")));
        expectError("INVALID_DATASET", "execute_data_driven_scenario", request("empty-list", steps, List.of()));
        expectError("INVALID_DATASET", "execute_data_driven_scenario",
                request("empty-vars", steps, List.of(dataset("ds-1", Map.of()))));
    }

    @Test void rejectsExcessiveDatasetCountAndTotalStepLimit() {
        var singleStep = List.of(step("assert_page_title", Map.of("expected", "{{t}}")));
        var tooManyDatasets = new ArrayList<Map<String, Object>>();
        for (int i = 1; i <= 21; i++) {
            tooManyDatasets.add(dataset("ds-" + i, Map.of("t", "v" + i)));
        }
        expectError("INVALID_DATASET", "execute_data_driven_scenario", request("too-many-ds", singleStep, tooManyDatasets));

        // 10 datasets * 21 steps = 210 > MAX_TOTAL_STEPS (200)
        var twentyOneSteps = Collections.nCopies(21, step("assert_page_title", Map.of("expected", "{{t}}")));
        var tenDatasets = tooManyDatasets.subList(0, 10);
        expectError("INVALID_SCENARIO", "execute_data_driven_scenario", request("too-many-steps", twentyOneSteps, tenDatasets));

        // Excessive variable count (>20) and variable value length (>2048)
        var manyVars = new HashMap<String, Object>();
        for (int i = 1; i <= 21; i++) manyVars.put("k" + i, "v");
        expectError("INVALID_DATASET", "execute_data_driven_scenario",
                request("too-many-vars", singleStep, List.of(dataset("ds-1", manyVars))));
        expectError("INVALID_DATASET", "execute_data_driven_scenario",
                request("huge-var", singleStep, List.of(dataset("ds-1", Map.of("t", "x".repeat(2049))))));
    }

    @Test void rejectsUnknownOrNonDataDrivenExecutionIdAndRecordsNoSession() {
        expectError("EXECUTION_NOT_FOUND", "get_data_driven_results",
                Map.of("executionId", UUID.randomUUID().toString()));
        // Standard scenario execution ID must not be returned by get_data_driven_results.
        var standard = data("execute_test_scenario", Map.of("scenario", Map.of(
                "id", "std-1", "description", "Standard",
                "steps", List.of(step("assert_page_title", Map.of("expected", ""))))));
        expectError("EXECUTION_NOT_FOUND", "get_data_driven_results",
                Map.of("executionId", standard.get("executionId")));

        // Data-driven execution without browser records ERROR cleanly and is retrievable.
        var dd = data("execute_data_driven_scenario", request("no-browser",
                List.of(step("assert_page_title", Map.of("expected", "{{t}}"))),
                List.of(dataset("ds-1", Map.of("t", "a")), dataset("ds-2", Map.of("t", "b")))));
        assertEquals("ERROR", dd.get("status"));
        var fetched = data("get_data_driven_results", Map.of("executionId", dd.get("executionId")));
        assertEquals("no-browser", fetched.get("scenarioName"));
        assertEquals("ERROR", fetched.get("status"));
        var dsList = (List<?>) fetched.get("datasets");
        assertEquals(1, dsList.size());
        var firstDs = (Map<?, ?>) dsList.getFirst();
        assertEquals("ds-1", firstDs.get("datasetId"));
        assertEquals("BROWSER_SESSION", firstDs.get("failureCategory"));
        var firstStep = (Map<?, ?>) ((List<?>) firstDs.get("steps")).getFirst();
        assertEquals("NO_SESSION", firstStep.get("code"));
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @EnabledIfSystemProperty(named = "browser.test", matches = "chrome|firefox|edge")
    class Browser {
        private HttpServer http;
        private HttpServer otherOrigin;
        private String origin;
        private String disallowedOrigin;
        private String browser;
        private Path artifacts;
        private final AtomicInteger loginHits = new AtomicInteger();

        @BeforeAll void setup(@TempDir Path directory) throws Exception {
            artifacts = directory;
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            int port = http.getAddress().getPort();
            origin = "http://127.0.0.1:" + port;
            otherOrigin = DisallowedOriginFixture.start();
            disallowedOrigin = "http://127.0.0.1:" + otherOrigin.getAddress().getPort();

            String loginTemplate;
            byte[] httpOnlyPage;
            try (var loginRes = DataDrivenScenarioTest.class.getResourceAsStream("/data-driven-login.html");
                 var httpOnlyRes = DataDrivenScenarioTest.class.getResourceAsStream("/data-driven-httponly.html")) {
                loginTemplate = new String(Objects.requireNonNull(loginRes).readAllBytes(), StandardCharsets.UTF_8)
                        .replace("DISALLOWED_ORIGIN", disallowedOrigin)
                        .replace("ALLOWED_ORIGIN", origin);
                httpOnlyPage = Objects.requireNonNull(httpOnlyRes).readAllBytes();
            }

            http.createContext("/login", ex -> {
                try (ex) {
                    loginHits.incrementAndGet();
                    String cookieHeader = ex.getRequestHeaders().getFirst("Cookie");
                    String httpOnlySeen = (cookieHeader != null && cookieHeader.contains("httponly_sess="))
                            ? "present" : "none";
                    byte[] bytes = loginTemplate.replace("HTTP_ONLY_SEEN", httpOnlySeen).getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    ex.sendResponseHeaders(200, bytes.length);
                    ex.getResponseBody().write(bytes);
                }
            });

            http.createContext("/set-httponly", ex -> {
                try (ex) {
                    ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    ex.getResponseHeaders().add("Set-Cookie", "httponly_sess=secret-session; Path=/; HttpOnly");
                    ex.sendResponseHeaders(200, httpOnlyPage.length);
                    ex.getResponseBody().write(httpOnlyPage);
                }
            });

            http.createContext("/", ex -> {
                try (ex) {
                    byte[] bytes = "<!doctype html><title>Root</title><p>Root</p>".getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    ex.sendResponseHeaders(200, bytes.length);
                    ex.getResponseBody().write(bytes);
                }
            });

            http.start();
            tools = new BrowserTools(sessions, new Origins(origin), new ExecutionHistory(artifacts, 50));
            browser = System.getProperty("browser.test");
            data("launch_browser", Map.of("browser", browser));
        }

        @AfterAll void stop() {
            sessions.shutdown();
            if (http != null) http.stop(0);
            if (otherOrigin != null) otherOrigin.stop(0);
        }

        private List<Map<String, Object>> loginSteps() {
            return List.of(
                    step("navigate", Map.of("url", origin + "/login")),
                    step("type_text", Map.of("selector", "#username", "text", "{{username}}")),
                    step("type_text", Map.of("selector", "#password", "text", "{{password}}")),
                    step("click_element", Map.of("selector", "#submit")),
                    step("assert_element_text", Map.of("selector", "#status", "expected", "{{expectedStatus}}")));
        }

        @Test void executesMultipleValidDatasetsAndRetrievesResults() {
            var datasets = List.of(
                    dataset("alice-valid", Map.of("username", "alice", "password", "valid-pass", "expectedStatus", "Welcome alice")),
                    dataset("bob-valid", Map.of("username", "bob", "password", "valid-pass", "expectedStatus", "Welcome bob")),
                    dataset("carol-denied", Map.of("username", "carol", "password", "wrong-pass", "expectedStatus", "Access denied for carol")));
            var result = data("execute_data_driven_scenario", request("multi-login", loginSteps(), datasets));
            assertEquals("PASS", result.get("status"));
            assertEquals("multi-login", result.get("scenarioName"));
            assertEquals(browser, result.get("browser"));
            assertEquals(3, result.get("plannedDatasets"));
            assertEquals(3, result.get("executedDatasets"));
            assertEquals(15, result.get("executedSteps"));

            var summary = (Map<?, ?>) result.get("summary");
            assertEquals(3, summary.get("passedDatasets"));
            assertEquals(0, summary.get("failedDatasets"));
            assertEquals(0, summary.get("errorDatasets"));
            assertEquals(0, summary.get("skippedDatasets"));

            String id = (String) result.get("executionId");
            var fetched = data("get_data_driven_results", Map.of("executionId", id));
            assertEquals("multi-login", fetched.get("scenarioName"));
            assertEquals("PASS", fetched.get("status"));
            var dsResults = (List<?>) fetched.get("datasets");
            assertEquals(3, dsResults.size());
            assertEquals(List.of("alice-valid", "bob-valid", "carol-denied"),
                    dsResults.stream().map(d -> ((Map<?, ?>) d).get("datasetId")).toList());
            for (Object d : dsResults) {
                var dsMap = (Map<?, ?>) d;
                assertEquals("PASS", dsMap.get("status"));
                assertEquals(5, dsMap.get("executedSteps"));
                assertEquals(5, dsMap.get("passedSteps"));
                assertNotNull(dsMap.get("durationMs"));
            }
            var reports = (Map<?, ?>) fetched.get("reports");
            assertEquals(List.of("json", "html"), reports.get("exportableFormats"));
        }

        @Test void continuesAfterFailingDatasetAndCapturesEvidence() throws Exception {
            var datasets = List.of(
                    dataset("user-1", Map.of("username", "alice", "password", "valid-pass", "expectedStatus", "Welcome alice")),
                    dataset("user-2-fail", Map.of("username", "bob", "password", "wrong-pass", "expectedStatus", "Welcome bob")),
                    dataset("user-3", Map.of("username", "carol", "password", "valid-pass", "expectedStatus", "Welcome carol")));
            var result = data("execute_data_driven_scenario", request("one-failure", loginSteps(), datasets));
            assertEquals("FAIL", result.get("status"));
            var summary = (Map<?, ?>) result.get("summary");
            assertEquals(2, summary.get("passedDatasets"));
            assertEquals(1, summary.get("failedDatasets"));
            assertEquals(0, summary.get("errorDatasets"));
            assertEquals(0, summary.get("skippedDatasets"));

            var dsResults = (List<?>) result.get("datasets");
            assertEquals("PASS", ((Map<?, ?>) dsResults.get(0)).get("status"));
            var failed = (Map<?, ?>) dsResults.get(1);
            assertEquals("FAIL", failed.get("status"));
            assertEquals("ASSERTION_FAILURE", failed.get("failureCategory"));
            assertEquals("PASS", ((Map<?, ?>) dsResults.get(2)).get("status"));

            var evidence = (List<?>) result.get("evidence");
            assertEquals(1, evidence.size());
            var ev = (Map<?, ?>) evidence.getFirst();
            assertEquals(2, ev.get("datasetIndex"));
            assertEquals("user-2-fail", ev.get("datasetId"));
            assertEquals(5, ev.get("step"));
            String relPath = (String) ev.get("path");
            assertTrue(relPath.endsWith("-d2-5.png"), relPath);
            Path image = artifacts.resolve(relPath);
            assertArrayEquals(new byte[]{-119, 80, 78, 71}, Arrays.copyOf(Files.readAllBytes(image), 4));
        }

        @Test void isolatesVariablesAndResetsCookiesLocalStorageAndSessionStorageBetweenDatasets() {
            // Pollute browser state before running the data-driven scenario (including HttpOnly cookie).
            data("navigate", Map.of("url", origin + "/set-httponly"));

            // Step template verifies prior cookies (including HttpOnly), localStorage, and sessionStorage are all "none"
            // before logging in and mutating them, proving both pre-run and inter-dataset reset isolation.
            var steps = List.of(
                    step("navigate", Map.of("url", origin + "/login")),
                    step("assert_element_text", Map.of("selector", "#prior-httponly", "expected", "none")),
                    step("assert_element_text", Map.of("selector", "#prior-cookie", "expected", "none")),
                    step("assert_element_text", Map.of("selector", "#prior-local", "expected", "none")),
                    step("assert_element_text", Map.of("selector", "#prior-session", "expected", "none")),
                    step("type_text", Map.of("selector", "#username", "text", "{{username}}")),
                    step("type_text", Map.of("selector", "#password", "text", "{{password}}")),
                    step("click_element", Map.of("selector", "#submit")),
                    step("assert_element_text", Map.of("selector", "#status", "expected", "{{expected}}")));

            // Also verify single-pass substitution: literal "{{password}}" inside username must NOT expand password!
            var datasets = List.of(
                    dataset("iso-1", Map.of("username", "alice", "password", "valid-pass", "expected", "Welcome alice",
                            "extraOnlyInFirst", "never-shared")),
                    dataset("iso-2", Map.of("username", "{{password}}", "password", "valid-pass",
                            "expected", "Welcome {{password}}")),
                    dataset("iso-3", Map.of("username", "charlie", "password", "valid-pass", "expected", "Welcome charlie")));

            var result = data("execute_data_driven_scenario", request("isolation-check", steps, datasets));
            assertEquals("PASS", result.get("status"), result::toString);
            assertEquals(3, ((Map<?, ?>) result.get("summary")).get("passedDatasets"));

            // Also verify HttpOnly cookie set DURING dataset 1 is cleared before dataset 2.
            var httpOnlySteps = List.of(
                    step("navigate", Map.of("url", origin + "/login")),
                    step("assert_element_text", Map.of("selector", "#prior-httponly", "expected", "none")),
                    step("assert_element_text", Map.of("selector", "#prior-cookie", "expected", "none")),
                    step("assert_element_text", Map.of("selector", "#prior-local", "expected", "none")),
                    step("assert_element_text", Map.of("selector", "#prior-session", "expected", "none")),
                    step("navigate", Map.of("url", "{{setUrl}}")));
            var httpOnlyDatasets = List.of(
                    dataset("dirty-1", Map.of("setUrl", origin + "/set-httponly")),
                    dataset("clean-2", Map.of("setUrl", origin + "/set-httponly")));
            var resetRes = data("execute_data_driven_scenario", request("httponly-reset", httpOnlySteps, httpOnlyDatasets));
            assertEquals("PASS", resetRes.get("status"), resetRes::toString);
        }

        @Test void stopsRemainingDatasetsWhenStopOnFailureIsTrue() {
            int before = loginHits.get();
            var datasets = List.of(
                    dataset("first-fail", Map.of("username", "alice", "password", "wrong-pass", "expectedStatus", "Welcome alice")),
                    dataset("second-skipped", Map.of("username", "bob", "password", "valid-pass", "expectedStatus", "Welcome bob")),
                    dataset("third-skipped", Map.of("username", "carol", "password", "valid-pass", "expectedStatus", "Welcome carol")));
            var req = request("stop-early", loginSteps(), datasets);
            req.put("stopOnFailure", true);
            var result = data("execute_data_driven_scenario", req);
            assertEquals("FAIL", result.get("status"));
            assertEquals(1, result.get("executedDatasets"));
            var summary = (Map<?, ?>) result.get("summary");
            assertEquals(1, summary.get("failedDatasets"));
            assertEquals(2, summary.get("skippedDatasets"));
            assertEquals(before + 1, loginHits.get());
        }

        @Test void enforcesOverallDeadlineAcrossDatasets() {
            var steps = List.of(
                    step("navigate", Map.of("url", origin + "/login")),
                    step("wait_for_element", Map.of("selector", "#never-appears", "condition", "presence", "timeoutSeconds", 30)),
                    step("assert_page_title", Map.of("expected", "{{title}}")));
            var datasets = List.of(
                    dataset("ds-timeout-1", Map.of("title", "Data-Driven Fixture")),
                    dataset("ds-timeout-2", Map.of("title", "Data-Driven Fixture")));
            var req = request("deadline-test", steps, datasets);
            req.put("timeoutSeconds", 1);
            var result = data("execute_data_driven_scenario", req);
            assertEquals("ERROR", result.get("status"));
            assertEquals(1, result.get("executedDatasets"));
            var summary = (Map<?, ?>) result.get("summary");
            assertEquals(1, summary.get("errorDatasets"));
            assertEquals(1, summary.get("skippedDatasets"));
            var ds1 = (Map<?, ?>) ((List<?>) result.get("datasets")).getFirst();
            assertEquals("TIMEOUT", ds1.get("failureCategory"));
            var ds1Steps = (List<?>) ds1.get("steps");
            var timedOutStep = (Map<?, ?>) ds1Steps.getLast();
            assertEquals("SCENARIO_TIMEOUT", timedOutStep.get("code"));
            assertTrue(((Number) result.get("durationMs")).longValue() < 10000);
        }

        @Test void blocksOriginDeniedInPrevalidationAndRuntimeRedirects() {
            // 1. Prevalidation rejects disallowed origin in dataset 2 with zero browser hits.
            int before = loginHits.get();
            var navSteps = List.of(step("navigate", Map.of("url", "{{target}}")));
            var badDatasets = List.of(
                    dataset("ok-ds", Map.of("target", origin + "/login")),
                    dataset("evil-ds", Map.of("target", disallowedOrigin + "/login")));
            expectError("ORIGIN_DENIED", "execute_data_driven_scenario", request("origin-preflight", navSteps, badDatasets));
            assertEquals(before, loginHits.get());

            // 2. Runtime navigation to disallowed origin via link click records ORIGIN_DENIED, blocks evidence,
            //    and resets cleanly so dataset 2 succeeds.
            var runtimeSteps = List.of(
                    step("navigate", Map.of("url", "{{url}}")),
                    step("click_element", Map.of("selector", "#leave")),
                    step("assert_page_title", Map.of("expected", "Data-Driven Fixture")));
            var runtimeDatasets = List.of(
                    dataset("click-disallowed", Map.of("url", origin + "/login")));
            var runtimeRes = data("execute_data_driven_scenario", request("origin-runtime", runtimeSteps, runtimeDatasets));
            assertEquals("ERROR", runtimeRes.get("status"));
            var ds1 = (Map<?, ?>) ((List<?>) runtimeRes.get("datasets")).getFirst();
            assertEquals("ORIGIN_DENIED", ds1.get("failureCategory"));
            var step2 = (Map<?, ?>) ((List<?>) ds1.get("steps")).get(1);
            assertEquals("ORIGIN_DENIED", step2.get("code"));
            assertEquals("CONTEXT_BLOCKED", step2.get("screenshotStatus"));

            // Verify browser recovered cleanly after ORIGIN_DENIED error.
            var recovery = data("execute_data_driven_scenario", request("post-origin-recovery",
                    List.of(step("navigate", Map.of("url", "{{url}}")),
                            step("assert_page_title", Map.of("expected", "{{title}}"))),
                    List.of(dataset("recover-1", Map.of("url", origin + "/login", "title", "Data-Driven Fixture")))));
            assertEquals("PASS", recovery.get("status"));
        }

        @Test void redactsSensitiveValuesAndExportsJsonAndHtmlReports() throws Exception {
            String secretPassword = "SUPER-SECRET-PASSWORD-991";
            String secretUser = "PRIVATE-USER-XYZ-777";
            String secretExpectedFail = "SECRET-EXPECTED-MISMATCH-555";
            var datasets = List.of(
                    dataset("dataset-alpha", Map.of("username", secretUser, "password", secretPassword,
                            "expectedStatus", "Welcome " + secretUser)),
                    dataset("dataset-beta", Map.of("username", secretUser, "password", secretPassword,
                            "expectedStatus", secretExpectedFail)));
            var req = request("redact-and-report", loginSteps(), datasets);
            req.put("collectConsole", true);
            var result = data("execute_data_driven_scenario", req);
            assertEquals("FAIL", result.get("status"));
            String id = (String) result.get("executionId");

            var fetched = data("get_data_driven_results", Map.of("executionId", id));
            var exec = data("get_test_execution", Map.of("executionId", id));
            var ev = data("get_execution_evidence", Map.of("executionId", id));
            var listed = (List<?>) data("list_test_executions", Map.of()).get("executions");
            assertEquals(id, ((Map<?, ?>) listed.getFirst()).get("executionId"));

            var jsonRef = data("export_test_report", Map.of("executionId", id, "format", "json"));
            var htmlRef = data("export_test_report", Map.of("executionId", id, "format", "html"));
            String jsonText = Files.readString(artifacts.resolve((String) jsonRef.get("path")));
            String htmlText = Files.readString(artifacts.resolve((String) htmlRef.get("path")));

            // Verify JSON parses cleanly and HTML contains structured dataset info + CSP + screenshot link.
            Map<?, ?> parsedJson = McpJsonDefaults.getMapper().readValue(jsonText, Map.class);
            assertEquals("redact-and-report", parsedJson.get("scenarioName"));
            assertTrue(htmlText.contains("Content-Security-Policy"));
            assertTrue(htmlText.contains("dataset-alpha"));
            assertTrue(htmlText.contains("dataset-beta"));
            assertTrue(htmlText.contains("ASSERTION_FAILURE"));
            assertTrue(htmlText.contains(id + "-d2-5.png"));
            assertFalse(htmlText.contains("base64"));

            // Verify get_data_driven_results now exposes exported report references.
            var afterExport = data("get_data_driven_results", Map.of("executionId", id));
            var exportedReports = (Map<?, ?>) ((Map<?, ?>) afterExport.get("reports")).get("exported");
            assertEquals(jsonRef.get("path"), exportedReports.get("json"));
            assertEquals(htmlRef.get("path"), exportedReports.get("html"));

            // Verify zero sensitive variable values leaked anywhere.
            for (String secret : List.of(secretPassword, secretUser, secretExpectedFail)) {
                assertFalse(result.toString().contains(secret), "Leaked in execute response: " + secret);
                assertFalse(fetched.toString().contains(secret), "Leaked in get_data_driven_results: " + secret);
                assertFalse(exec.toString().contains(secret), "Leaked in get_test_execution: " + secret);
                assertFalse(ev.toString().contains(secret), "Leaked in get_execution_evidence: " + secret);
                assertFalse(jsonText.contains(secret), "Leaked in JSON report: " + secret);
                assertFalse(htmlText.contains(secret), "Leaked in HTML report: " + secret);
            }

            // Verify expired/deleted screenshot is flagged as EXPIRED_OR_UNAVAILABLE.
            var evItems = (List<?>) ev.get("evidence");
            assertEquals(1, evItems.size());
            Path screenshotFile = artifacts.resolve((String) ((Map<?, ?>) evItems.getFirst()).get("path"));
            Files.delete(screenshotFile);
            var afterDelete = data("get_data_driven_results", Map.of("executionId", id));
            assertEquals(List.of(), afterDelete.get("evidence"));
            var ds2 = (Map<?, ?>) ((List<?>) afterDelete.get("datasets")).get(1);
            var ds2LastStep = (Map<?, ?>) ((List<?>) ds2.get("steps")).getLast();
            assertEquals("EXPIRED_OR_UNAVAILABLE", ds2LastStep.get("screenshotStatus"));
        }

        @Test void cleansUpExtraWindowsAndDeadBrowserSessionAfterErrors() {
            // 1. Dataset opens a popup window and then errors on a missing element -> extra window is cleaned up.
            var popupSteps = List.of(
                    step("navigate", Map.of("url", "{{url}}")),
                    step("click_element", Map.of("selector", "#popup")),
                    step("click_element", Map.of("selector", "#nonexistent-button")));
            var popupRes = data("execute_data_driven_scenario", request("popup-error-cleanup", popupSteps,
                    List.of(dataset("ds-popup", Map.of("url", origin + "/login")))));
            assertEquals("ERROR", popupRes.get("status"));
            int windowCount = sessions.use(d -> d.getWindowHandles().size());
            assertEquals(1, windowCount);

            // 2. Existing execute_test_scenario continues working seamlessly alongside data-driven tool.
            var std = data("execute_test_scenario", Map.of("scenario", Map.of(
                    "id", "compat-check", "description", "Compatibility check",
                    "steps", List.of(
                            step("navigate", Map.of("url", origin + "/login")),
                            step("assert_page_title", Map.of("expected", "Data-Driven Fixture"))))));
            assertEquals("PASS", std.get("status"));

            // 3. Dead browser session mid-run produces retrievable ERROR and cleans up so launch_browser works next.
            sessions.use(d -> { d.quit(); return null; });
            try {
                var deadRes = data("execute_data_driven_scenario", request("dead-session",
                        List.of(step("navigate", Map.of("url", "{{url}}"))),
                        List.of(
                                dataset("ds-dead-1", Map.of("url", origin + "/login")),
                                dataset("ds-dead-2", Map.of("url", origin + "/login")))));
                assertEquals("ERROR", deadRes.get("status"));
                var dsList = (List<?>) deadRes.get("datasets");
                assertEquals(2, dsList.size());
                assertEquals("BROWSER_SESSION", ((Map<?, ?>) dsList.get(0)).get("failureCategory"));
                assertEquals("RESET_FAILURE", ((Map<?, ?>) dsList.get(1)).get("failureCategory"));
                assertEquals("DATASET_RESET_FAILED", ((Map<?, ?>) dsList.get(1)).get("code"));
                assertEquals("ERROR", data("get_data_driven_results",
                        Map.of("executionId", deadRes.get("executionId"))).get("status"));
            } finally {
                // Because the dead session was cleaned up quietly, launch_browser must succeed directly.
                data("launch_browser", Map.of("browser", browser));
            }
        }
    }
}
