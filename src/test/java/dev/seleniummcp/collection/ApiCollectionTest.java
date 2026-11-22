package dev.seleniummcp.collection;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.seleniummcp.api.ApiTools;
import dev.seleniummcp.reporting.ExecutionHistory;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ApiCollectionTest {
    private static final String EXECUTE = "execute_api_collection";
    private static final String GET = "get_api_collection_results";
    private static final String LIST = "list_api_collection_executions";
    private static final String EXPORT = "export_api_collection_report";
    private static final String SECRET = "fixture-credential";
    @TempDir Path artifacts;
    private final List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch unblock = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService workers;
    private ApiTools api;
    private ApiCollectionTools tools;
    private String origin;

    @BeforeEach void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext("/", this::respond);
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        api = new ApiTools(origin, true);
        tools = new ApiCollectionTools(api, new ExecutionHistory(artifacts, ApiCollectionTools.HISTORY_LIMIT));
    }

    @AfterEach void cleanup() {
        unblock.countDown();
        api.close();
        server.stop(0);
        workers.shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE"})
    void supportsEachMethod(String method) {
        var row = step("one", "/ok");
        row.put("method", method);
        assertEquals("PASS", execute(List.of(row), Map.of()).get("status"));
        assertEquals(List.of(method + " /ok"), calls);
    }

    @Test void extractsSecretsAndNullAndRunsInSequence() {
        var login = step("login", "/login");
        login.put("extract", Map.of("token", "$.token", "item", "$.items[0].id", "empty", "$.empty"));
        var create = step("create", "/create/{{item}}?value={{query}}");
        create.put("method", "POST");
        create.put("headers", Map.of("Authorization", "Bearer {{token}}"));
        create.put("body", "{\"empty\":{{empty}}}");
        create.put("assertions", List.of(Map.of("type", "status", "expectedStatus", 201),
                Map.of("type", "json", "jsonPath", "$.valid", "assertionType", "equals", "expected", true)));
        var verification = step("verify", "/ok");
        verification.put("assertions", List.of(Map.of("type", "json", "jsonPath", "$.none", "assertionType", "not_exists")));
        var summary = execute(List.of(login, create, verification), Map.of("variables", Map.of("query", "a&b=x")));
        assertEquals(3, summary.get("passed"));
        assertEquals(List.of("GET /login", "POST /create/a%2Fb?value=a%26b%3Dx", "GET /ok"), calls);
        var result = result(summary);
        assertFalse(result.toString().contains(SECRET));
        assertFalse(result.toString().contains("a%26b"));
        assertFalse(result.toString().contains("Authorization"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void stopsOrContinuesOnMismatch(boolean stop) {
        var failed = step("first", "/missing");
        failed.put("assertions", List.of(Map.of("type", "status", "expectedStatus", 200)));
        var result = execute(List.of(failed, step("second", "/ok")), Map.of("stopOnFailure", stop));
        assertEquals("FAIL", result.get("status"));
        assertEquals(stop ? 1 : 2, calls.size());
        assertEquals(stop ? 1 : 0, result.get("skipped"));
    }

    @Test void treatsHttpErrorsAsCompletedResponses() {
        var missing = step("missing", "/missing");
        missing.put("assertions", List.of(Map.of("type", "status", "expectedStatus", 404)));
        assertEquals(2, execute(List.of(missing, step("server", "/server-error")), Map.of()).get("passed"));
    }

    @Test void checksRedirectStatusWithoutFollowingLocation() {
        var redirect = step("redirect", "/redirect");
        redirect.put("assertions", List.of(Map.of("type", "status", "expectedStatus", 302)));
        var summary = execute(List.of(redirect, step("next", "/ok")), Map.of());
        assertEquals(2, summary.get("passed"));
        assertEquals(List.of("GET /redirect", "GET /ok"), calls);
        assertFalse(result(summary).toString().contains("/redirect-target"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "unknown-variable", "bad-path", "bad-template", "injected-header",
            "invalid-origin", "origin-template", "large-body", "disallowed-header"})
    void prevalidatesEntirePlan(String problem) {
        var last = step("second", "/ok");
        switch (problem) {
            case "duplicate" -> last.put("id", "first");
            case "unknown-variable" -> last.put("body", "{{unknown}}");
            case "bad-path" -> last.put("extract", Map.of("x", "$..bad"));
            case "bad-template" -> last.put("body", "{{broken");
            case "injected-header" -> last.put("headers", Map.of("Authorization", "Bearer {{bad}}"));
            case "invalid-origin" -> last.put("url", "http://127.0.0.1:1/ok");
            case "origin-template" -> last.put("url", "http://127.0.0.1:{{port}}/ok");
            case "large-body" -> last.put("body", "a".repeat(65537));
            case "disallowed-header" -> last.put("headers", Map.of("Host", "{{bad}}"));
            default -> fail("Invalid fixture");
        }
        var result = call(EXECUTE, Map.of("collectionName", "Validation", "requests", List.of(step("first", "/ok"), last),
                "variables", Map.of("bad", "ok\r\nInjected: true", "port", "1")));
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(calls.isEmpty(), calls::toString);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/invalid-json", "/missing-property"})
    void missingOrInvalidExtractionDoesNotPublishVariables(String endpoint) {
        var producer = step("producer", endpoint);
        producer.put("extract", Map.of("output", "$.value"));
        var summary = execute(List.of(producer, step("consumer", "/use/{{output}}")), Map.of("stopOnFailure", false));
        assertEquals(2, summary.get("errors"));
        assertEquals(1, calls.size());
        assertEquals("COLLECTION_VARIABLE_MISSING", row(result(summary), 1).get("code"));
    }

    @Test void validatesExpandedHeadersBeforeSending() {
        var producer = step("producer", "/header-value");
        producer.put("extract", Map.of("token", "$.token"));
        var consumer = step("consumer", "/ok");
        consumer.put("headers", Map.of("Authorization", "Bearer {{token}}"));
        assertEquals(1, execute(List.of(producer, consumer), Map.of()).get("errors"));
        assertEquals(1, calls.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void honorsBothDeadlines(boolean collectionDeadline) {
        var slow = step("slow", "/slow");
        slow.put("timeoutSeconds", collectionDeadline ? 4 : 1);
        var summary = execute(List.of(slow, step("second", "/ok")),
                Map.of("timeoutSeconds", collectionDeadline ? 1 : 8, "stopOnFailure", false));
        assertEquals(collectionDeadline ? "COLLECTION_TIMEOUT" : "API_TIMEOUT", row(result(summary), 0).get("code"));
        assertEquals(collectionDeadline ? 1 : 2, calls.size());
    }

    @Test void rejectsOversizedResponses() {
        var summary = execute(List.of(step("large", "/large")), Map.of());
        assertEquals("API_RESPONSE_TOO_LARGE", row(result(summary), 0).get("code"));
    }

    @Test void exportsOnlySanitizedReportsAndEscapesLabels() throws IOException {
        var step = step("login", "/login?secret=fixture-query");
        step.put("name", "<script>fixture</script>");
        step.put("extract", Map.of("token", "$.token"));
        var summary = execute(List.of(step), Map.of("collectionName", "Fixture <b>name</b>"));
        for (String format : List.of("json", "html")) {
            var exported = ok(call(EXPORT, Map.of("executionId", summary.get("executionId"), "format", format)));
            Path path = artifacts.resolve((String) exported.get("path"));
            assertTrue(path.normalize().startsWith(artifacts));
            String report = Files.readString(path);
            assertFalse(report.contains(SECRET));
            assertFalse(report.contains("fixture-query"));
            if (format.equals("html")) {
                assertTrue(report.contains("&lt;script&gt;"));
                assertFalse(report.contains("<script>"));
            }
        }
        assertEquals(Boolean.TRUE, call(EXPORT, Map.of("executionId", "../outside", "format", "json")).isError());
        assertEquals(Boolean.TRUE, call(EXPORT, Map.of("executionId", summary.get("executionId"), "format", "../outside")).isError());
    }

    @Test void boundsHistoryAndReturnsNewestFirst() {
        var first = execute(List.of(step("one", "/empty")), Map.of());
        Map<?, ?> last = null;
        for (int i = 0; i < 32; i++) last = execute(List.of(step("one", "/empty")), Map.of());
        assertEquals(Boolean.TRUE, call(GET, Map.of("executionId", first.get("executionId"))).isError());
        var summaries = (List<?>) ok(call(LIST, Map.of("limit", 32))).get("executions");
        assertEquals(32, summaries.size());
        assertEquals(last.get("executionId"), ((Map<?, ?>) summaries.getFirst()).get("executionId"));
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            var path = exchange.getRequestURI().getRawPath();
            if (path.equals("/slow")) {
                try { unblock.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return;
            }
            if (path.equals("/empty")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (path.equals("/redirect")) {
                exchange.getResponseHeaders().set("Location", "/redirect-target");
                exchange.sendResponseHeaders(302, -1);
                return;
            }
            int status = switch (path) {
                case "/missing" -> 404;
                case "/server-error" -> 500;
                case "/create/a%2Fb" -> 201;
                default -> 200;
            };
            String response = switch (path) {
                case "/login" -> "{\"token\":\"" + SECRET + "\",\"items\":[{\"id\":\"a/b\"}],\"empty\":null}";
                case "/invalid-json" -> "{";
                case "/header-value" -> "{\"token\":\"bad\\r\\nInjected: true\"}";
                case "/large" -> "a".repeat(131073);
                case "/create/a%2Fb" -> "{\"valid\":" + (
                        ("Bearer " + SECRET).equals(exchange.getRequestHeaders().getFirst("Authorization"))
                        && "{\"empty\":null}".equals(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))) + "}";
                default -> "{}";
            };
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private Map<String, Object> step(String id, String path) {
        return new LinkedHashMap<>(Map.of("id", id, "name", id, "method", "GET", "url", origin + path));
    }

    private Map<?, ?> execute(List<?> steps, Map<String, Object> options) {
        var args = new LinkedHashMap<String, Object>(Map.of("collectionName", "Fixture", "requests", steps));
        args.putAll(options);
        return ok(call(EXECUTE, args));
    }

    private Map<?, ?> result(Map<?, ?> summary) {
        return ok(call(GET, Map.of("executionId", summary.get("executionId"))));
    }

    private static Map<?, ?> row(Map<?, ?> result, int index) {
        return (Map<?, ?>) ((List<?>) result.get("requests")).get(index);
    }

    private CallToolResult call(String name, Map<String, Object> args) {
        return tools.specifications().stream().filter(spec -> spec.tool().name().equals(name)).findFirst().orElseThrow()
                .callHandler().apply(null, new CallToolRequest(name, args));
    }

    private static Map<?, ?> ok(CallToolResult result) {
        assertNotEquals(Boolean.TRUE, result.isError(), result::toString);
        return (Map<?, ?>) result.structuredContent();
    }
}
