package dev.seleniummcp;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openqa.selenium.JavascriptExecutor;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdvancedToolsTest {
    private final BrowserSession sessions = new BrowserSession();
    private BrowserTools tools = new BrowserTools(sessions, new Origins(""));
    private HttpServer site, other;
    private String origin;

    private CallToolResult call(String tool, Map<String, Object> args) {
        var spec = tools.specifications().stream().filter(s -> s.tool().name().equals(tool)).findFirst().orElseThrow();
        return spec.callHandler().apply(null, new CallToolRequest(tool, args));
    }

    private CallToolResult ok(String tool, Map<String, Object> args) {
        var result = call(tool, args);
        assertNotEquals(Boolean.TRUE, result.isError(), () -> tool + " " + result.structuredContent());
        return result;
    }

    private void error(String expected, String tool, Map<String, Object> args) {
        var result = call(tool, args);
        assertEquals(Boolean.TRUE, result.isError(), tool);
        assertEquals(expected, ((Map<?, ?>) result.structuredContent()).get("code"), tool);
    }

    @Test void validatesBeforeDriverAccess() {
        error("INVALID_INPUT", "find_interactive_elements", Map.of("selector", "*"));
        error("INVALID_INPUT", "get_element_details", Map.of("strategy", "css"));
        error("INVALID_INPUT", "hover_element", Map.of("strategy", "script", "selector", "button"));
        for (Object timeout : List.of(-1, 31, 1.5, "5", 4294967296L)) {
            error("INVALID_INPUT", "wait_for_element", Map.of("selector", "p", "condition", "presence", "timeoutSeconds", timeout));
        }
        error("INVALID_INPUT", "select_dropdown", Map.of("selector", "select", "index", 0, "value", ""));
        error("INVALID_INPUT", "scroll_page", Map.of("direction", "down", "selector", "p"));
        error("INVALID_INPUT", "switch_frame", Map.of("index", 1, "name", "f"));
        error("INVALID_INPUT", "switch_window", Map.of("handle", 1));
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @EnabledIfSystemProperty(named = "browser.test", matches = "chrome|firefox|edge")
    class Browser {
        private HttpServer serve(String page) throws Exception {
            var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/", exchange -> {
                try (exchange) {
                    String path = exchange.getRequestURI().getPath();
                    if (path.equals("/redirect")) {
                        exchange.getResponseHeaders().set("Location", address(other) + "/child");
                        exchange.sendResponseHeaders(302, -1);
                        return;
                    }
                    String html = switch (path) {
                        case "/child" -> "<p id='inside'>Child</p><button id='leave' onclick=\"location.href='" + address(other) + "/outside'\">Leave</button>";
                        case "/editor" -> "<iframe srcdoc=\"<p id='inside'>Editor</p><iframe srcdoc='nested'></iframe>\"></iframe>";
                        case "/opaque" -> "<iframe sandbox srcdoc='<p>private</p>'></iframe>";
                        case "/opaque-http" -> "<iframe sandbox src='/child'></iframe>";
                        case "/empty" -> "<main>Empty</main>";
                        default -> page;
                    };
                    byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            http.start();
            return http;
        }

        private String address(HttpServer server) {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @BeforeAll void setup() throws Exception {
            other = serve("<main>Other origin</main>");
            try (var resource = AdvancedToolsTest.class.getResourceAsStream("/advanced.html")) {
                String page = new String(Objects.requireNonNull(resource).readAllBytes(), StandardCharsets.UTF_8);
                site = serve(page.replace("DENIED_ORIGIN", address(other)));
            }
            origin = address(site);
            tools = new BrowserTools(sessions, new Origins(origin));
            ok("launch_browser", Map.of("browser", System.getProperty("browser.test")));
        }

        @BeforeEach void open() { ok("navigate", Map.of("url", origin)); }

        @AfterAll void shutdown() {
            sessions.shutdown();
            if (site != null) site.stop(0);
            if (other != null) other.stop(0);
        }

        private Map<?, ?> data(String name, Map<String, Object> args) {
            return (Map<?, ?>) ok(name, args).structuredContent();
        }

        @Test void discoveryAndDetails() {
            var rows = (List<?>) data("find_interactive_elements", Map.of()).get("elements");
            var row = rows.stream().map(Map.class::cast).filter(r -> "query".equals(r.get("id"))).findFirst().orElseThrow();
            assertEquals("Search terms", row.get("accessibleLabel"));
            assertEquals(Map.of("strategy", "id", "selector", "query"), row.get("locator"));
            var checked = data("get_element_details", Map.of("strategy", "id", "selector", "checked"));
            assertEquals(true, checked.get("selected"));
            assertEquals(true, checked.get("visible"));
            assertEquals(false, data("get_element_details", Map.of("selector", "#disabled")).get("enabled"));
            error("INVALID_SELECTOR", "get_element_details", Map.of("selector", "["));
            ok("navigate", Map.of("url", origin + "/empty"));
            assertEquals(List.of(), data("find_interactive_elements", Map.of()).get("elements"));
        }

        @Test void redirectsRevalidateTheResultingDocument() {
            error("ORIGIN_DENIED", "navigate", Map.of("url", origin + "/redirect"));
            error("ORIGIN_DENIED", "get_page_text", Map.of());
            error("ORIGIN_DENIED", "take_screenshot", Map.of());
            ok("navigate", Map.of("url", origin));
        }

        @Test void freshBrowserHasIsolatedCookies() {
            sessions.use(d -> {
                d.manage().addCookie(new org.openqa.selenium.Cookie("fixture", "isolated"));
                return null;
            });
            ok("close_browser", Map.of());
            ok("launch_browser", Map.of("browser", System.getProperty("browser.test")));
            ok("navigate", Map.of("url", origin));
            assertNull(sessions.use(d -> d.manage().getCookieNamed("fixture")));
        }

        @Test void waits() {
            ok("wait_for_element", Map.of("selector", "#hidden", "condition", "presence", "timeoutSeconds", 0));
            ok("wait_for_element", Map.of("selector", "query", "strategy", "name", "condition", "visibility"));
            ok("wait_for_element", Map.of("selector", "#hover", "condition", "clickability"));
            for (String condition : List.of("visibility", "clickability")) {
                error("TIMEOUT", "wait_for_element", Map.of("selector", "#hidden", "condition", condition, "timeoutSeconds", 0));
            }
            error("TIMEOUT", "wait_for_element", Map.of("selector", "#missing", "condition", "presence", "timeoutSeconds", 0));
        }

        @Test void inheritedFrameChecks() {
            ok("navigate", Map.of("url", origin + "/editor"));
            ok("switch_frame", Map.of("index", 0));
            assertEquals("Editor", data("get_element_details", Map.of("selector", "#inside")).get("text"));
            error("ORIGIN_DENIED", "switch_frame", Map.of("index", 0));
            ok("get_element_details", Map.of("selector", "iframe"));
            for (String path : List.of("/opaque", "/opaque-http")) {
                ok("navigate", Map.of("url", origin + path));
                error("ORIGIN_DENIED", "switch_frame", Map.of("index", 0));
                ok("get_element_details", Map.of("selector", "iframe"));
            }
        }

        @Test void disappearanceWaits() {
            for (String condition : List.of("absence", "invisibility")) {
                ok("wait_for_element", Map.of("selector", "#missing", "condition", condition, "timeoutSeconds", 0));
                error("TIMEOUT", "wait_for_element", Map.of("selector", "#query", "condition", condition, "timeoutSeconds", 0));
            }
            ok("wait_for_element", Map.of("selector", "#hidden", "condition", "invisibility"));
            error("TIMEOUT", "wait_for_element", Map.of("selector", "#hidden", "condition", "absence", "timeoutSeconds", 0));
            error("TIMEOUT", "wait_for_element", Map.of("selector", "#hidden,#bottom", "condition", "invisibility", "timeoutSeconds", 0));
        }

        @Test void dropdown() {
            for (var pick : List.of(Map.of("visibleText", "Berry"), Map.of("index", 1), Map.of("value", ""))) {
                var args = new HashMap<String, Object>(pick);
                args.put("selector", "#menu");
                ok("select_dropdown", args);
                int selected = pick.containsKey("index") ? 2 : pick.containsKey("value") ? 1 : 3;
                assertEquals(true, data("get_element_details", Map.of("selector", "#menu option:nth-child(" + selected + ")")).get("selected"));
            }
            error("UNSUPPORTED_FIELD", "select_dropdown", Map.of("selector", "#query", "index", 0));
            error("OPTION_NOT_FOUND", "select_dropdown", Map.of("selector", "#menu", "index", 99));
        }

        private long offset() {
            return sessions.use(d -> ((Number) ((JavascriptExecutor) d).executeScript("return window.scrollY")).longValue());
        }

        @Test void scrollAndHover() {
            ok("scroll_page", Map.of("direction", "down"));
            long down = offset();
            assertTrue(down > 0);
            ok("scroll_page", Map.of("direction", "up"));
            assertTrue(offset() < down);
            ok("scroll_page", Map.of("selector", "#bottom"));
            assertTrue(offset() >= down);
            ok("hover_element", Map.of("selector", "#hover"));
            assertEquals("Hovered", data("get_element_details", Map.of("selector", "output")).get("text"));
            error("INVALID_SELECTOR", "scroll_page", Map.of("selector", "["));
            error("TIMEOUT", "hover_element", Map.of("selector", "#hidden"));
        }

        @Test void frames() {
            for (var spec : List.of(Map.of("index", 0), Map.of("id", "child"), Map.of("name", "childFrame"), Map.of("selector", "iframe#child"))) {
                ok("switch_frame", new HashMap<>(spec));
                assertEquals("Child", data("get_element_details", Map.of("selector", "#inside")).get("text"));
                ok("switch_frame", Map.of());
            }
            error("ORIGIN_DENIED", "switch_frame", Map.of("id", "blocked"));
            error("FRAME_NOT_FOUND", "switch_frame", Map.of("index", 20));
            ok("switch_frame", Map.of("id", "child"));
            error("ORIGIN_DENIED", "click_element", Map.of("selector", "#leave"));
            error("ORIGIN_DENIED", "get_page_text", Map.of());
            ok("switch_frame", Map.of());
            ok("get_page_text", Map.of());
        }

        private List<?> handles() { return (List<?>) data("switch_window", Map.of()).get("handles"); }

        @Test void windows() {
            String original = sessions.use(d -> d.getWindowHandle());
            for (String link : List.of("#popup", "#blockedPopup")) {
                var before = handles();
                ok("click_element", Map.of("selector", link));
                String opened = handles().stream().filter(h -> !before.contains(h)).findFirst().orElseThrow().toString();
                if (link.equals("#popup")) {
                    ok("switch_window", Map.of("handle", opened));
                    ok("get_element_details", Map.of("selector", "#inside"));
                    ok("switch_window", Map.of("handle", original));
                } else {
                    error("ORIGIN_DENIED", "switch_window", Map.of("handle", opened));
                    ok("get_element_details", Map.of("selector", "#query"));
                }
            }
            error("WINDOW_NOT_FOUND", "switch_window", Map.of("handle", "unknown"));
        }
    }
}
