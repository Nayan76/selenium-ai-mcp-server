package dev.seleniummcp.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.seleniummcp.browser.BrowserSession;
import dev.seleniummcp.security.Origins;
import dev.seleniummcp.tools.BrowserTools;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ProtocolException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ApiToolsTest {
    private static final String SEND = "send_api_request";
    private static final String STATUS = "assert_api_status";
    private static final String RESPONSE = "assert_api_response";
    private static final String HISTORY = "get_api_execution_results";
    private final CountDownLatch releaseSlowResponse = new CountDownLatch(1);
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private ExecutorService executor;
    private String origin;
    private ApiTools tools;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        tools = new ApiTools(origin, true);
    }

    @AfterEach void stop() {
        releaseSlowResponse.countDown();
        if (tools != null) tools.close();
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @ParameterizedTest
    @CsvSource({"GET,200", "POST,201", "PUT,200", "PATCH,200", "DELETE,200"})
    void sendsMethods(String method, int statusCode) {
        var args = new LinkedHashMap<String, Object>(Map.of("method", method, "url", origin + "/echo"));
        if (List.of("POST", "PUT", "PATCH").contains(method)) args.put("body", "{\"message\":\"fixture\"}");
        var result = ok(call(SEND, args));
        assertEquals(statusCode, result.get("statusCode"));
        String id = (String) result.get("requestId");
        checkJson(id, "$.method", "equals", method, "PASS");
        if (args.containsKey("body")) checkJson(id, "$.jsonReceived", "equals", true, "PASS");
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 400, 404, 500})
    void httpErrorStatusesAreResponses(int statusCode) {
        String id = send("/status/" + statusCode);
        assertEquals("PASS", checkStatus(id, statusCode).get("status"));
        assertEquals("FAIL", checkStatus(id, 599).get("status"));
        assertEquals(statusCode, ok(call(HISTORY, Map.of("requestId", id))).get("statusCode"));
    }

    @Test void assertsNestedJsonAndArrayMembership() {
        String id = send("/json");
        checkJson(id, "$.User.name", "equals", "Ada", "PASS");
        checkJson(id, "$.values[0]", "equals", 1, "PASS");
        checkJson(id, "$.values[1]", "equals", 9007199254740993L, "PASS");
        checkJson(id, "$.values", "contains", Map.of("x", true), "PASS");
        checkJson(id, "$.User.name", "contains", "d", "PASS");
        checkJson(id, "$.User.name", "equals", "unknown", "FAIL");
        checkJson(id, "$.User", "equals", Map.of("name", "Ada"), "PASS");
        checkJson(id, "$.values[0]", "type", "number", "PASS");
        checkJson(id, "$.User", "type", "array", "FAIL");
    }

    @Test void distinguishesMissingFromNull() {
        String id = send("/json");
        checkPresence(id, "$.absent", "not_exists", "PASS");
        checkPresence(id, "$.values[99].x", "exists", "FAIL");
        checkPresence(id, "$.values[0].x", "not_exists", "PASS");
        checkPresence(id, "$.nothing", "exists", "PASS");
        checkPresence(id, "$.nothing", "not_exists", "FAIL");
        checkJson(id, "$.nothing", "equals", null, "PASS");
        checkJson(id, "$.absent", "equals", null, "FAIL");
        checkJson(id, "$.nothing", "type", "null", "PASS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/invalid", "/duplicate", "/trailing", "/json-depth"})
    void reportsInvalidJson(String path) {
        String id = send(path);
        error(call(RESPONSE, jsonArgs(id, "$", "exists")), "API_INVALID_JSON");
        assertEquals("PASS", checkStatus(id, 200).get("status"));
    }

    @ParameterizedTest
    @CsvSource({"/empty,EMPTY", "/text,TEXT"})
    void acceptsEmptyAndPlainText(String path, String kind) {
        var result = ok(call(SEND, Map.of("method", "GET", "url", origin + path)));
        assertEquals(kind, ((Map<?, ?>) result.get("response")).get("kind"));
        assertFalse(result.toString().contains("private-text"));
        error(call(RESPONSE, jsonArgs((String) result.get("requestId"), "$", "exists")), "API_NOT_JSON");
    }

    @ParameterizedTest
    @ValueSource(strings = {"$..name", "$.values[*]", "$.values[-1]", "$['User']", "$[?(@.x)]", "$.values[01]"})
    void rejectsUnsupportedPaths(String expression) {
        error(call(RESPONSE, jsonArgs(send("/json"), expression, "exists")), "API_UNSUPPORTED_PATH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/slow-headers", "/slow-body"})
    void deadlineIncludesHeadersAndBody(String path) {
        var error = error(call(SEND, Map.of("method", "GET", "url", origin + path, "timeoutSeconds", 1)), "API_TIMEOUT");
        assertTrue(requests.get() > 0);
        assertEquals("API_TIMEOUT", ok(call(HISTORY, Map.of("requestId", error.get("requestId")))).get("code"));
    }

    @Test void reportsConnectionFailure() {
        server.stop(0);
        var error = error(call(SEND, Map.of("method", "GET", "url", origin + "/json", "timeoutSeconds", 2)),
                "API_TRANSPORT_ERROR");
        error(call(STATUS, Map.of("requestId", error.get("requestId"), "expectedStatus", 200)),
                "API_RESPONSE_UNAVAILABLE");
    }

    @Test void requiresIndependentOriginAndDevelopmentOptIn() {
        try (var denied = new ApiTools("", true); var production = new ApiTools(origin, false)) {
            var args = Map.<String, Object>of("method", "GET", "url", origin + "/json");
            error(call(denied.specifications(), SEND, args), "API_ORIGIN_DENIED");
            error(call(production.specifications(), SEND, args), "API_ADDRESS_DENIED");
            assertEquals(0, requests.get());
            send("/json");
            assertEquals(1, requests.get());
        }
    }

    @Test void failsClosedWhenAnyConfiguredOriginIsMalformed() {
        try (var malformed = new ApiTools(origin + ",http://user:fixture-secret@127.0.0.1", true)) {
            var args = Map.<String, Object>of("method", "GET", "url", origin + "/json");
            var result = call(malformed.specifications(), SEND, args);
            error(result, "API_CONFIGURATION_INVALID");
            assertFalse(result.toString().contains("fixture-secret"));
            assertEquals(0, requests.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "api.example.test", "127.000.0.1", "127.0.0.2",
            "10.0.0.3", "172.16.0.2", "192.168.0.3", "100.100.100.200", "169.254.169.254",
            "168.63.129.16", "224.0.0.1", "[::1]", "[::ffff:127.0.0.1]"})
    void refusesDnsAndNonPublicAddressesEvenWhenConfigured(String host) {
        String target = "http://" + host;
        try (var configured = new ApiTools(target, true)) {
            error(call(configured.specifications(), SEND, Map.of("method", "GET", "url", target + "/")),
                    "API_ADDRESS_DENIED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///path", "ftp://127.0.0.1/", "data:text/plain,example", "javascript:void(0)",
            "http://user:secret@127.0.0.1/", "http://127.0.0.1:0/", "http://127.0.0.1/#hidden", "http://127.1/"})
    void rejectsUnsafeUrls(String url) {
        error(call(SEND, Map.of("method", "GET", "url", url)), "INVALID_INPUT");
        assertEquals(0, requests.get());
    }

    @Test void redirectsAreReturnedWithoutFollowingOrExposingLocation() {
        var result = ok(call(SEND, Map.of("method", "GET", "url", origin + "/redirect")));
        assertEquals(302, result.get("statusCode"));
        assertEquals(1, requests.get());
        assertFalse(result.toString().contains("redirect-secret"));
    }

    @Test void ignoresDefaultProxy() {
        var previous = ProxySelector.getDefault();
        ProxySelector.setDefault(ProxySelector.of(new InetSocketAddress("127.0.0.1", 1)));
        try (var direct = new ApiTools(origin, true)) {
            ok(call(direct.specifications(), SEND, Map.of("method", "GET", "url", origin + "/json")));
            assertEquals(1, requests.get());
        } finally {
            ProxySelector.setDefault(previous);
        }
    }

    @ParameterizedTest
    @CsvSource({"/oversized,API_RESPONSE_TOO_LARGE", "/chunked,API_RESPONSE_TOO_LARGE",
            "/compressed,API_ENCODING_UNSUPPORTED"})
    void boundsResponseResourceUse(String path, String code) {
        var result = error(call(SEND, Map.of("method", "GET", "url", origin + path)), code);
        assertEquals(200, result.get("statusCode"));
        error(call(RESPONSE, jsonArgs((String) result.get("requestId"), "$", "exists")), "API_RESPONSE_UNAVAILABLE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/large-header", "/large-header-total"})
    void parserRejectsOversizedHeadersBeforeBodyHandler(String path) {
        try (var client = HttpClient.newBuilder().proxy(ProxySelector.of(null))
                .version(HttpClient.Version.HTTP_1_1).build()) {
            var request = HttpRequest.newBuilder(URI.create(origin + path)).build();
            assertThrows(ProtocolException.class, () -> client.send(request, info -> {
                fail("The JDK must reject oversized headers before the body handler runs.");
                return HttpResponse.BodySubscribers.discarding();
            }));
        }
        var result = error(call(SEND, Map.of("method", "GET", "url", origin + path)), "API_TRANSPORT_ERROR");
        assertFalse(result.containsKey("statusCode"));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 404})
    void acceptsHeadersWithinParserLimit(int status) {
        var result = ok(call(SEND, Map.of("method", "GET", "url", origin + "/header-ok/" + status)));
        assertEquals(status, result.get("statusCode"));
    }

    @Test void stripsSecretsAndNeverRecordsRequestOrAssertionValues() {
        var sent = call(SEND, Map.of("method", "POST", "url", origin + "/json/secret-path?token=secret-query",
                "headers", Map.of("Authorization", "Bearer secret-header", "Cookie", "fixture=secret-cookie"),
                "body", "secret-request"));
        String id = (String) ok(sent).get("requestId");
        checkJson(id, "$.User.name", "equals", "secret-expected", "FAIL");
        var protectedResult = call(RESPONSE, jsonArgs(id, "$.User.access_token", "exists"));
        error(protectedResult, "API_SENSITIVE_PATH");
        var history = call(HISTORY, Map.of("requestId", id));
        assertEquals(origin, ok(history).get("origin"));
        for (var result : List.of(sent, protectedResult, history)) {
            for (String secret : List.of("secret-path", "secret-query", "secret-header", "secret-cookie",
                    "secret-request", "secret-expected", "secret-response", "secret-set-cookie", "access_token")) {
                assertFalse(result.toString().contains(secret), secret);
            }
        }
    }

    @Test void rejectsInvalidInputsBeforeSending() {
        for (var extra : List.<Map<String, Object>>of(Map.of("method", "TRACE"),
                Map.of("headers", Map.of("X-Test", "good\r\nHost: bad")), Map.of("headers", Map.of("X:Bad", "x")),
                Map.of("headers", Map.of("Host", "bad")), Map.of("headers", Map.of("X-Test", "x", "x-test", "y")),
                Map.of("body", "é".repeat(ApiRequestValidator.MAX_BODY / 2 + 1)),
                Map.of("timeoutSeconds", 31), Map.of("timeoutSeconds", 1.5), Map.of("extra", true))) {
            var args = new LinkedHashMap<String, Object>(Map.of("method", "POST", "url", origin + "/json"));
            args.putAll(extra);
            error(call(SEND, args), "INVALID_INPUT");
        }
        assertEquals(0, requests.get());
    }

    @Test void rejectsInvalidAndMissingIds() {
        error(call(HISTORY, Map.of("requestId", "secret-invalid-id")), "API_INVALID_REQUEST_ID");
        error(call(STATUS, Map.of("requestId", UUID.randomUUID().toString(), "expectedStatus", 200)), "API_REQUEST_NOT_FOUND");
    }

    @Test void boundsHistoryAndPerRequestAssertions() {
        String first = send("/empty");
        for (int i = 0; i < ApiExecutionHistory.MAX_REQUESTS; i++) send("/empty");
        error(call(HISTORY, Map.of("requestId", first)), "API_REQUEST_NOT_FOUND");
        String id = send("/json");
        for (int i = 0; i <= ApiExecutionHistory.MAX_ASSERTIONS; i++) checkStatus(id, 200 + i);
        var assertions = (List<?>) ok(call(HISTORY, Map.of("requestId", id))).get("assertions");
        assertEquals(ApiExecutionHistory.MAX_ASSERTIONS, assertions.size());
        assertEquals(201, ((Map<?, ?>) assertions.getFirst()).get("expectedStatus"));
    }

    @Test
    @EnabledIfSystemProperty(named = "browser.test", matches = "chrome|firefox|edge")
    void combinesSeparateApiAndBrowserSessions() {
        String id = send("/json");
        checkStatus(id, 200);
        checkJson(id, "$.User.name", "equals", "Ada", "PASS");
        var session = new BrowserSession();
        try {
            var browser = new BrowserTools(session, new Origins(origin)).specifications();
            ok(call(browser, "launch_browser", Map.of("browser", System.getProperty("browser.test"))));
            ok(call(browser, "navigate", Map.of("url", origin + "/page")));
            assertEquals("PASS", ok(call(browser, "assert_page_title",
                    Map.of("expected", "Fresh browser"))).get("status"));
            checkJson(send("/probe"), "$.fresh", "equals", true, "PASS");
            assertEquals(2, ((List<?>) ok(call(HISTORY, Map.of("requestId", id))).get("assertions")).size());
        } finally {
            session.shutdown();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/echo" -> {
                    boolean hasJson = "application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))
                            && new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                            .equals("{\"message\":\"fixture\"}");
                    respond(exchange, exchange.getRequestMethod().equals("POST") ? 201 : 200, "application/json",
                            "{\"method\":\"" + exchange.getRequestMethod() + "\",\"jsonReceived\":" + hasJson + "}");
                }
                case "/invalid" -> respond(exchange, 200, "application/json", "{broken");
                case "/duplicate" -> respond(exchange, 200, "application/json", "{\"x\":1,\"x\":2}");
                case "/trailing" -> respond(exchange, 200, "application/json", "{}{}");
                case "/json-depth" -> respond(exchange, 200, "application/json", "[".repeat(33) + "0" + "]".repeat(33));
                case "/empty" -> exchange.sendResponseHeaders(204, -1);
                case "/text" -> respond(exchange, 200, "text/plain", "private-text");
                case "/slow-headers" -> awaitRelease();
                case "/slow-body" -> {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write('x');
                    exchange.getResponseBody().flush();
                    awaitRelease();
                }
                case "/redirect" -> {
                    exchange.getResponseHeaders().set("Location", origin + "/probe?token=redirect-secret");
                    exchange.sendResponseHeaders(302, -1);
                }
                case "/oversized" -> respond(exchange, 200, "text/plain", "x".repeat(ApiClient.MAX_RESPONSE_BYTES + 1));
                case "/chunked" -> {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(new byte[ApiClient.MAX_RESPONSE_BYTES + 1]);
                }
                case "/large-header" -> {
                    exchange.getResponseHeaders().set("X-Large", "x".repeat(17 * 1024));
                    exchange.sendResponseHeaders(200, -1);
                }
                case "/large-header-total" -> {
                    exchange.getResponseHeaders().set("X-First", "a".repeat(9 * 1024));
                    exchange.getResponseHeaders().set("X-Second", "b".repeat(9 * 1024));
                    exchange.sendResponseHeaders(200, -1);
                }
                case "/header-ok/200", "/header-ok/404" -> {
                    exchange.getResponseHeaders().set("X-Near-Limit", "a".repeat(15 * 1024));
                    exchange.sendResponseHeaders(Integer.parseInt(path.substring(path.lastIndexOf('/') + 1)), -1);
                }
                case "/compressed" -> {
                    exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                    exchange.sendResponseHeaders(200, -1);
                }
                case "/page" -> {
                    boolean fresh = exchange.getRequestHeaders().getFirst("Cookie") == null;
                    exchange.getResponseHeaders().set("Set-Cookie", "browserOnly=1; Path=/");
                    respond(exchange, 200, "text/html", "<title>" + (fresh ? "Fresh browser" : "Cookie present") + "</title>");
                }
                case "/probe" -> respond(exchange, 200, "application/json",
                        "{\"fresh\":" + (exchange.getRequestHeaders().getFirst("Cookie") == null) + "}");
                default -> {
                    if (path.startsWith("/status/")) {
                        respond(exchange, Integer.parseInt(path.substring(8)), "text/plain", "fixture");
                    } else {
                        exchange.getResponseHeaders().set("Set-Cookie", "apiOnly=secret-set-cookie; Path=/");
                        respond(exchange, 200, "application/json",
                                "{\"User\":{\"name\":\"Ada\",\"access_token\":\"secret-response\"},"
                                + "\"values\":[1.0,9007199254740993,{\"x\":true}],\"nothing\":null}");
                    }
                }
            }
        }
    }

    private void awaitRelease() {
        try {
            releaseSlowResponse.await();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(HttpExchange exchange, int status, String media, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", media);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private String send(String path) {
        return (String) ok(call(SEND, Map.of("method", "GET", "url", origin + path))).get("requestId");
    }

    private Map<?, ?> checkStatus(String id, int expected) {
        return ok(call(STATUS, Map.of("requestId", id, "expectedStatus", expected)));
    }

    private void checkPresence(String id, String path, String type, String status) {
        assertEquals(status, ok(call(RESPONSE, jsonArgs(id, path, type))).get("status"));
    }

    private void checkJson(String id, String path, String type, Object expected, String status) {
        var args = jsonArgs(id, path, type);
        args.put("expected", expected);
        assertEquals(status, ok(call(RESPONSE, args)).get("status"));
    }

    private static Map<String, Object> jsonArgs(String id, String path, String type) {
        return new LinkedHashMap<>(Map.of("requestId", id, "jsonPath", path, "assertionType", type));
    }

    private CallToolResult call(String name, Map<String, Object> args) {
        return call(tools.specifications(), name, args);
    }

    private static CallToolResult call(List<SyncToolSpecification> specs, String name, Map<String, Object> args) {
        var spec = specs.stream().filter(s -> s.tool().name().equals(name)).findFirst().orElseThrow();
        return spec.callHandler().apply(null, new CallToolRequest(name, args));
    }

    private static Map<?, ?> ok(CallToolResult result) {
        assertNotEquals(Boolean.TRUE, result.isError(), result::toString);
        return (Map<?, ?>) result.structuredContent();
    }

    private static Map<?, ?> error(CallToolResult result, String code) {
        assertEquals(Boolean.TRUE, result.isError(), result::toString);
        var data = (Map<?, ?>) result.structuredContent();
        assertEquals(code, data.get("code"), result::toString);
        assertEquals("ERROR", data.get("status"));
        return data;
    }
}
