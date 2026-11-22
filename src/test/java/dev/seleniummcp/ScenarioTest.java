package dev.seleniummcp;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioTest {
    private final BrowserSession sessions = new BrowserSession();
    private BrowserTools tools = new BrowserTools(sessions, new Origins(""));

    private CallToolResult call(String name, Map<String, Object> args) {
        var spec = tools.specifications().stream().filter(s -> name.equals(s.tool().name())).findFirst().orElseThrow();
        return spec.callHandler().apply(null, new CallToolRequest(name, args));
    }

    private Map<?, ?> data(String name, Map<String, Object> args) {
        var result = call(name, args);
        assertNotEquals(Boolean.TRUE, result.isError(), () -> name + " " + result.structuredContent());
        return (Map<?, ?>) result.structuredContent();
    }

    private static Map<String, Object> step(String name, Map<String, Object> args) {
        return Map.of("tool", name, "arguments", args);
    }

    private static Map<String, Object> scenario(List<?> steps) {
        return new HashMap<>(Map.of("id", "fixture-01", "description", "Fixture check", "steps", steps));
    }

    private Map<?, ?> runCase(Map<String, Object> scenario) {
        return data("execute_test_scenario", Map.of("scenario", scenario));
    }

    private static List<?> rows(Map<?, ?> report) {
        return (List<?>) report.get("steps");
    }

    private static void assertStatus(String expected, Map<?, ?> result) {
        assertEquals(expected, result.get("status"), result::toString);
    }

    @Test void rejectsInvalidDocumentsAndUnboundedOrUnsupportedSteps() {
        assertTrue(call("get_test_execution_results", Map.of()).isError());
        var valid = step("assert_page_title", Map.of("expected", ""));
        for (Object malformed : List.of("{", List.of(), Map.of(), scenario(List.of()),
                scenario(Collections.nCopies(33, valid)), scenario(List.of(step("execute_test_scenario", Map.of()))),
                scenario(List.of(step("execute_script", Map.of()))), scenario(List.of(step("navigate", Map.of("url", "file:///tmp")))),
                scenario(List.of(step("assert_element_attribute", Map.of("selector","input","attribute","value","expected","")))),
                scenario(List.of(step("wait_for_element", Map.of("selector","p","condition","presence","timeoutSeconds",31)))))) {
            assertTrue(call("execute_test_scenario", Map.of("scenario", malformed)).isError());
        }
        for (Object invalid : List.of(0, 61, 1.5, "1")) {
            var bad = scenario(List.of(valid));
            bad.put("timeoutSeconds", invalid);
            assertTrue(call("execute_test_scenario", Map.of("scenario", bad)).isError());
        }
    }

    @Test void executionWithoutBrowserIsRecordedAsError() {
        var result = runCase(scenario(List.of(step("assert_page_title", Map.of("expected", "")))));
        assertStatus("ERROR", result);
        assertEquals("NO_SESSION", ((Map<?, ?>) rows(result).getFirst()).get("code"));
        assertEquals("UNAVAILABLE", ((Map<?, ?>) rows(result).getFirst()).get("screenshotStatus"));
        assertEquals(result, data("get_test_execution_results", Map.of()));
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @EnabledIfSystemProperty(named = "browser.test", matches = "chrome|firefox|edge")
    class Browser {
        private HttpServer http;
        private String origin;
        private String browser;
        private final Map<String, Object> title = Map.of("expected", "Local tests");
        private static final String PRIVATE_MARKER = "private-fixture-marker";

        private Path artifacts;

        @BeforeAll void setup(@TempDir Path directory) throws Exception {
            artifacts = directory;
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            origin = "http://127.0.0.1:" + http.getAddress().getPort();
            String page = """
                    <!doctype html><title>Local tests</title><h1 id="title">Hello team</h1>
                    <input id="entry"><input id="hidden" hidden><p>%s</p>
                    <a id="leave" href="%s">Leave</a>
                    """.formatted(PRIVATE_MARKER, origin.replace("127.0.0.1", "localhost"));
            http.createContext("/", ex -> {
                try (ex) {
                    byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");
                    ex.sendResponseHeaders(200, bytes.length);
                    ex.getResponseBody().write(bytes);
                }
            });
            http.start();
            tools = new BrowserTools(sessions, new Origins(origin), new ExecutionHistory(artifacts, 50));
            browser = System.getProperty("browser.test");
            data("launch_browser", Map.of("browser", browser));
        }

        @BeforeEach void open() { data("navigate", Map.of("url", origin)); }

        @AfterAll void stop() {
            sessions.shutdown();
            if (http != null) http.stop(0);
        }

        @Test void recordsEvidenceExportsAndLookup() throws Exception {
            var test = scenario(List.of(step("assert_page_title", title), step("assert_page_title", Map.of("expected", PRIVATE_MARKER))));
            var result = runCase(test);
            assertStatus("FAIL", result);
            assertEquals(browser, result.get("browser"));
            String id = (String) result.get("executionId");
            var lookup = Map.<String, Object>of("executionId", id);
            assertEquals(result, data("get_test_execution", lookup));
            var recent = (List<?>) data("list_test_executions", Map.of()).get("executions");
            assertEquals(id, ((Map<?, ?>) recent.getFirst()).get("executionId"));
            var items = (List<?>) data("get_execution_evidence", lookup).get("evidence");
            assertEquals(1, items.size());
            var evidence = (Map<?, ?>) items.getFirst();
            assertEquals(2, evidence.get("step"));
            assertEquals(id, evidence.get("executionId"));
            Path image = artifacts.resolve((String) evidence.get("path"));
            assertArrayEquals(new byte[]{-119,80,78,71}, Arrays.copyOf(Files.readAllBytes(image),4));
            for (String format : List.of("html","json")) {
                var ref = data("export_test_report", Map.of("executionId", id, "format", format));
                String output = Files.readString(artifacts.resolve((String) ref.get("path")));
                assertTrue(output.contains(image.getFileName().toString()));
                assertFalse(output.contains(PRIVATE_MARKER));
                assertFalse(output.contains("base64"));
            }
            var passing = scenario(List.of(step("assert_page_title", title)));
            passing.put("screenshotOnSuccess", true);
            passing.put("collectConsole", true);
            var pass = runCase(passing);
            assertStatus("PASS", pass);
            assertEquals(1, ((List<?>) pass.get("evidence")).size());
            assertTrue(pass.containsKey("console"));
            assertNotEquals(id, pass.get("executionId"));
        }

        @Test void failedScreenshotWritePreservesFailure(@TempDir Path dir) throws Exception {
            var normal = tools;
            try {
                Path file = Files.createFile(dir.resolve("unusable"));
                tools = new BrowserTools(sessions, new Origins(origin), new ExecutionHistory(file,1));
                var failure = runCase(scenario(List.of(step("assert_page_title", Map.of("expected","Other")))));
                assertStatus("FAIL", failure);
                var row = (Map<?, ?>) rows(failure).getFirst();
                assertEquals("Expectation mismatch.", row.get("message"));
                assertEquals("UNAVAILABLE", row.get("screenshotStatus"));
            } finally {
                tools = normal;
            }
        }

        @Test void blocksEvidenceWithUnverifiableChildDocument() {
            sessions.use(d -> ((org.openqa.selenium.JavascriptExecutor) d)
                    .executeScript("document.body.append(document.createElement('iframe'))"));
            var result = runCase(scenario(List.of(step("assert_page_title", Map.of("expected","Other")))));
            assertStatus("FAIL", result);
            assertEquals(List.of(), result.get("evidence"));
            assertEquals("CONTEXT_BLOCKED", ((Map<?, ?>) rows(result).getFirst()).get("screenshotStatus"));
        }

        @Test void comparesVisibleHiddenAndMissingElements() {
            var visible = data("assert_element_visible", Map.of("selector","#title"));
            assertStatus("PASS", visible);
            assertEquals(true, visible.get("expected"));
            assertEquals(true, visible.get("actual"));
            for (String selector : List.of("#hidden","#absent")) {
                assertStatus("FAIL", data("assert_element_visible", Map.of("selector", selector)));
                assertStatus("PASS", data("assert_element_visible", Map.of("selector", selector, "visible", false)));
            }
            assertStatus("FAIL", data("assert_element_text", Map.of("selector","#absent","expected","")));
            assertStatus("FAIL", data("assert_element_attribute", Map.of("selector","h1","attribute","name","expected","")));
        }

        @Test void comparesTextAttributesTitlesUrlsAndCounts() {
            for (String match : List.of("exact","contains")) {
                String expected = match.equals("exact") ? "Hello team" : "team";
                assertStatus("PASS", data("assert_element_text", Map.of("selector","title","strategy","id","match",match,"expected",expected)));
                assertStatus("FAIL", data("assert_element_text", Map.of("selector","h1","match",match,"expected","Other")));
                assertStatus("PASS", data("assert_current_url", Map.of("match",match,"expected",match.equals("exact") ? origin + "/" : "127.0.0.1")));
                assertStatus("FAIL", data("assert_current_url", Map.of("match",match,"expected","absent")));
            }
            assertStatus("PASS", data("assert_page_title", title));
            assertStatus("FAIL", data("assert_page_title", Map.of("expected","Other")));
            assertStatus("PASS", data("assert_element_attribute", Map.of("selector","h1","attribute","id","expected","title")));
            assertStatus("FAIL", data("assert_element_attribute", Map.of("selector","h1","attribute","id","expected","other")));
            assertStatus("PASS", data("assert_element_count", Map.of("selector","input","count",2)));
            assertStatus("FAIL", data("assert_element_count", Map.of("selector","input","count",0)));
            assertStatus("PASS", data("assert_element_count", Map.of("selector","#absent","count",0)));
        }

        @Test void preflightsBeforeTypingAndSuppressesFreeText() {
            var type = step("type_text", Map.of("selector","#entry","text",PRIVATE_MARKER));
            var bad = scenario(List.of(type, step("navigate", Map.of("url","file:///tmp/"))));
            assertTrue(call("execute_test_scenario", Map.of("scenario",bad)).isError());
            assertEquals("", sessions.use(d -> d.findElement(org.openqa.selenium.By.id("entry")).getDomProperty("value")));
            var valid = scenario(List.of(type, step("assert_element_text", Map.of("selector","p","expected",PRIVATE_MARKER))));
            valid.put("description", PRIVATE_MARKER);
            var result = runCase(valid);
            assertStatus("PASS", result);
            assertFalse(result.toString().contains(PRIVATE_MARKER));
            assertFalse(call("get_test_execution_results", Map.of()).toString().contains(PRIVATE_MARKER));
        }

        @Test void stopsOrContinuesAndRetainsWorstStatus() {
            var test = scenario(List.of(step("assert_page_title", Map.of("expected","Other")), step("assert_page_title", title)));
            assertEquals(1, rows(runCase(test)).size());
            test.put("continueOnFailure", true);
            var result = runCase(test);
            assertStatus("FAIL", result);
            assertEquals(2, rows(result).size());
            assertStatus("PASS", (Map<?, ?>) rows(result).getLast());
            var invalidLocator = scenario(List.of(step("assert_element_count", Map.of("selector","[","count",0)), step("assert_page_title", title)));
            invalidLocator.put("continueOnFailure", true);
            assertStatus("ERROR", runCase(invalidLocator));
        }

        @Test void deadlineStopsPendingWaitAndLaterSteps() {
            var test = scenario(List.of(step("wait_for_element", Map.of("selector","#absent","condition","presence","timeoutSeconds",30)),
                    step("assert_page_title", title)));
            test.put("timeoutSeconds", 1);
            test.put("continueOnFailure", true);
            var result = runCase(test);
            assertStatus("ERROR", result);
            assertEquals(1, rows(result).size());
            assertEquals("SCENARIO_TIMEOUT", ((Map<?, ?>) rows(result).getFirst()).get("code"));
            assertTrue(((Number) result.get("durationMs")).longValue() < 10000);
        }

        @Test void rejectsDisallowedContextAfterClick() {
            var test = scenario(List.of(step("click_element", Map.of("selector","#leave")), step("assert_page_title",title)));
            test.put("continueOnFailure", true);
            var result = runCase(test);
            assertStatus("ERROR", result);
            for (Object row : rows(result)) assertEquals("ORIGIN_DENIED", ((Map<?, ?>) row).get("code"));
        }

        @Test void browserClosureProducesRetrievableError() {
            sessions.use(d -> { d.quit(); return null; });
            try {
                var result = runCase(scenario(List.of(step("assert_page_title",title))));
                assertStatus("ERROR", result);
                assertEquals(result, data("get_test_execution_results",Map.of()));
            } finally {
                data("close_browser", Map.of());
                data("launch_browser", Map.of("browser", browser));
            }
        }
    }
}
