package dev.seleniummcp;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class BrowserToolsTest {
    private final BrowserSession sessions = new BrowserSession();
    private BrowserTools tools = new BrowserTools(sessions, new Origins("https://example.com"));

    private CallToolResult call(String name, Map<String, Object> args) {
        var spec = tools.specifications().stream().filter(s -> s.tool().name().equals(name)).findFirst().orElseThrow();
        return spec.callHandler().apply(null, new CallToolRequest(name, args));
    }

    private CallToolResult success(String name, Map<String, Object> args) {
        var result = call(name, args);
        assertNotEquals(Boolean.TRUE, result.isError(), () -> name + " " + result.structuredContent());
        return result;
    }

    private void error(String code, String name, Map<String, Object> args) {
        var result = call(name, args);
        assertTrue(result.isError());
        assertEquals(code, ((Map<?, ?>) result.structuredContent()).get("code"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/a", "javascript:void(0)", "https://user@example.com", "//example.com"})
    void rejectsUnsafeUrl(String value) {
        error("INVALID_URL", "navigate", Map.of("url", value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com", "https://example.com:444", "https://example.com.invalid"})
    void requiresExactOrigin(String value) {
        error("ORIGIN_DENIED", "navigate", Map.of("url", value));
    }

    @Test void validatesBeforeLaunch() {
        error("INVALID_INPUT", "launch_browser", Map.of());
        error("INVALID_INPUT", "launch_browser", Map.of("browser", "safari"));
        error("INVALID_INPUT", "launch_browser", Map.of("browser", "chrome", "headless", "yes"));
        error("INVALID_INPUT", "launch_browser", Map.of("browser", "edge", "binary", "/tmp/browser"));
    }

    @Test void idleSessionAndShutdown() {
        success("close_browser", Map.of());
        error("NO_SESSION", "navigate", Map.of("url", "https://EXAMPLE.com:443/test"));
        sessions.shutdown();
        error("SHUTTING_DOWN", "launch_browser", Map.of("browser", "firefox"));
    }

    @Test
    @EnabledIfSystemProperty(named = "browser.test", matches = "chrome|firefox|edge")
    void browserRoundTrip() throws Exception {
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] page = """
                <html><body><input id="name"><input type="file" id="upload">
                <button onclick="document.querySelector('output').textContent=document.querySelector('#name').value">Apply</button>
                <output></output></body></html>
                """.getBytes(StandardCharsets.UTF_8);
        http.createContext("/", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, page.length);
                exchange.getResponseBody().write(page);
            }
        });
        http.start();
        try {
            String origin = "http://127.0.0.1:" + http.getAddress().getPort();
            tools = new BrowserTools(sessions, new Origins(origin));
            var launch = Map.<String, Object>of("browser", System.getProperty("browser.test"));
            success("launch_browser", launch);
            error("SESSION_ACTIVE", "launch_browser", launch);
            success("navigate", Map.of("url", origin));
            success("type_text", Map.of("selector", "#name", "text", "Phase One"));
            error("UNSUPPORTED_FIELD", "type_text", Map.of("selector", "#upload", "text", "file.txt"));
            success("click_element", Map.of("selector", "button"));
            var result = success("get_page_text", Map.of());
            assertTrue(((TextContent) result.content().getFirst()).text().contains("Phase One"));
            var found = success("find_elements", Map.of("selector", "input"));
            assertEquals(2, ((Map<?, ?>) found.structuredContent()).get("total"));
            var image = (ImageContent) success("take_screenshot", Map.of()).content().getFirst();
            assertEquals("image/png", image.mimeType());
            assertArrayEquals(new byte[]{-119, 80, 78, 71}, Arrays.copyOf(Base64.getDecoder().decode(image.data()), 4));
            success("close_browser", Map.of());
            error("NO_SESSION", "get_page_text", Map.of());
        } finally {
            sessions.shutdown();
            http.stop(0);
        }
    }
}
