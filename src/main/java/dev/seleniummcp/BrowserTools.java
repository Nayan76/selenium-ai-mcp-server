package dev.seleniummcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.LoggerFactory;

final class BrowserTools {
    private static final Map<String, Map<String, Object>> FIELDS = Map.ofEntries(
            Map.entry("browser", choices("chrome", "firefox", "edge")),
            Map.entry("headless", Map.of("type", "boolean", "default", true)),
            Map.entry("url", stringField(4096)), Map.entry("selector", stringField(1024)),
            Map.entry("text", stringField(8192)), Map.entry("strategy", choices("css", "id", "name")),
            Map.entry("condition", choices("presence", "visibility", "clickability", "invisibility", "absence")),
            Map.entry("timeoutSeconds", integer(0, 30)), Map.entry("index", integer(0, 65535)),
            Map.entry("value", Map.of("type", "string", "maxLength", 1024)),
            Map.entry("visibleText", stringField(1024)), Map.entry("direction", choices("up", "down")),
            Map.entry("id", stringField(1024)), Map.entry("name", stringField(1024)),
            Map.entry("handle", stringField(1024)), Map.entry("expected", Map.of("type", "string", "maxLength", 4096)),
            Map.entry("visible", Map.of("type", "boolean")), Map.entry("count", integer(0, Integer.MAX_VALUE)),
            Map.entry("match", choices("exact", "contains")),
            Map.entry("attribute", choices("id", "name", "class", "type", "role", "title", "alt", "disabled",
                    "checked", "aria-label", "aria-expanded", "aria-checked", "data-testid")),
            Map.entry("executionId", stringField(36)), Map.entry("format", choices("json", "html")),
            Map.entry("scenario", ScenarioEngine.SCHEMA));

    private static Map<String, Object> choices(String... options) {
        return Map.of("type", "string", "enum", List.of(options));
    }

    private static Map<String, Object> integer(int min, int max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max);
    }
    private final BrowserSession sessions;
    private final Origins origins;
    private final ScenarioEngine engine;

    private final ExecutionHistory history;

    BrowserTools(BrowserSession sessions, Origins origins) {
        this(sessions, origins, ExecutionHistory.configured());
    }

    BrowserTools(BrowserSession sessions, Origins origins, ExecutionHistory history) {
        this.history = history;
        this.sessions = sessions;
        this.origins = origins;
        this.engine = new ScenarioEngine(sessions, origins, history);
    }

    private static Map<String, Object> stringField(int limit) {
        return Map.of("type", "string", "minLength", 1, "maxLength", limit);
    }

    List<SyncToolSpecification> specifications() {
        var advanced = new AdvancedTools(origins);
        var tools = new ArrayList<>(List.of(
                tool("launch_browser", "Open a fresh browser. Headless defaults to true.", a -> {
                    sessions.launch((String) a.get("browser"), (boolean) a.getOrDefault("headless", true));
                    return text("Browser launched.");
                }, "browser", "headless?"),
                tool("navigate", "Load an allowed HTTP(S) URL.", a -> {
                    String url = (String) a.get("url");
                    origins.check(url);
                    return sessions.use(d -> {
                        d.get(url);
                        origins.check(d.getCurrentUrl());
                        return text("Page loaded.");
                    });
                }, "url"),
                tool("click_element", "Click the first clickable CSS match.", a -> onPage(d -> {
                    clickable(d, a).click();
                    return text("Clicked.");
                }), "selector"),
                tool("type_text", "Append literal text to a text input or textarea.", a -> onPage(d -> {
                    String value = (String) a.get("text");
                    WebElement field = clickable(d, a);
                    boolean textInput = field.getTagName().equalsIgnoreCase("input")
                            && Set.of("text", "search", "email", "url", "tel", "password", "number")
                                    .contains(field.getDomProperty("type"));
                    if (!textInput && !field.getTagName().equalsIgnoreCase("textarea")) {
                        throw new ToolFailure("UNSUPPORTED_FIELD", "Choose a text input or textarea.");
                    }
                    field.sendKeys(value);
                    return text("Text entered.");
                }), "selector", "text"),
                tool("get_page_text", "Read visible body text (up to 32,000 characters).", a -> onPage(d -> {
                    var body = waitFor(d).until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));
                    return text(limit(body.getText(), 32000));
                })),
                tool("find_elements", "Find CSS matches and summarize up to 50 by tag and visible text.", a -> onPage(d -> {
                    List<WebElement> matches;
                    try {
                        matches = waitFor(d).until(ExpectedConditions.presenceOfAllElementsLocatedBy(selector(a)));
                    } catch (TimeoutException empty) {
                        matches = List.of();
                    }
                    var rows = matches.stream().limit(50).map(e -> Map.of("tag", e.getTagName(),
                            "text", limit(e.getText(), 200))).toList();
                    return CallToolResult.builder().addTextContent(matches.size() + " matches: " + rows)
                            .structuredContent(Map.of("total", matches.size(), "elements", rows)).build();
                }), "selector"),
                tool("take_screenshot", "Return a PNG of the browser viewport.", a -> onPage(d -> {
                    byte[] bytes = BrowserEvidence.screenshot(d, origins);
                    return CallToolResult.builder().addContent(new ImageContent(null,
                            Base64.getEncoder().encodeToString(bytes), "image/png")).build();
                })),
                tool("close_browser", "Quit the active browser. Safe to repeat.", a -> {
                    sessions.close();
                    return text("Browser closed.");
                }),
                tool("find_interactive_elements", "Describe up to 50 interactive candidates in document order.",
                        a -> onPage(advanced::discover)),
                tool("wait_for_element", "Wait for presence, visibility, clickability, invisibility or absence (0–30 seconds, default 5).",
                        a -> onPage(d -> advanced.waitForElement(d, a)), "selector", "strategy?", "condition", "timeoutSeconds?"),
                tool("select_dropdown", "Choose exactly one option by visibleText, value or zero-based index.",
                        a -> {
                            requireOne(a, "visibleText", "value", "index");
                            return onPage(d -> advanced.select(d, a));
                        }, "selector", "strategy?", "visibleText?", "value?", "index?"),
                tool("scroll_page", "Scroll one viewport up/down or scroll to a located element.",
                        a -> {
                            requireOne(a, "direction", "selector");
                            return onPage(d -> advanced.scroll(d, a));
                        }, "direction?", "selector?", "strategy?"),
                tool("hover_element", "Move the pointer to a visible element.",
                        a -> onPage(d -> advanced.hover(d, a)), "selector", "strategy?"),
                tool("switch_frame", "Enter a child frame by index, id, name or selector. Empty arguments return to the default document.",
                        a -> {
                            if (a.isEmpty()) return sessions.use(advanced::defaultDocument);
                            requireOne(a, "index", "id", "name", "selector");
                            return onPage(d -> advanced.frame(d, a));
                        }, "index?", "id?", "name?", "selector?", "strategy?"),
                tool("switch_window", "List window handles or switch to an allowed window by handle.",
                        a -> sessions.use(d -> advanced.window(d, a)), "handle?"),
                tool("get_element_details", "Read element metadata and displayed, enabled and selected states.",
                        a -> onPage(d -> advanced.details(d, a)), "selector", "strategy?"),
                assertion("assert_element_visible", "Check whether the first match is visible.", "selector", "strategy?", "visible?"),
                assertion("assert_element_text", "Compare visible element text.", "selector", "strategy?", "expected", "match?"),
                assertion("assert_element_attribute", "Compare a supported DOM attribute.", "selector", "strategy?", "attribute", "expected"),
                assertion("assert_page_title", "Compare the top-level page title exactly.", "expected"),
                assertion("assert_current_url", "Compare the top-level URL.", "expected", "match?"),
                assertion("assert_element_count", "Count matches in the active document.", "selector", "strategy?", "count")));
        var steps = new HashMap<String, SyncToolSpecification>();
        for (var spec : tools) {
            if (ScenarioEngine.supports(spec.tool().name())) steps.put(spec.tool().name(), spec);
        }
        tools.add(tool("execute_test_scenario", "Validate and execute a bounded sequence of browser actions and assertions.",
                a -> engine.execute((Map<?, ?>) a.get("scenario"), steps), "scenario"));
        tools.add(tool("get_test_execution_results", "Retrieve the latest completed scenario in this server process.",
                a -> engine.latest()));
        tools.add(tool("list_test_executions", "List recent completed executions, newest first.",
                a -> reporting("Execution history.", Map.of("executions", history.list()))));
        tools.add(tool("get_test_execution", "Retrieve a retained execution report.",
                a -> reporting("Execution report.", history.get((String) a.get("executionId"))), "executionId"));
        tools.add(tool("export_test_report", "Write JSON or standalone HTML and return its relative path.",
                a -> reporting("Report exported.", history.export((String) a.get("executionId"), (String) a.get("format"))),
                "executionId", "format"));
        tools.add(tool("get_execution_evidence", "List screenshot metadata for a retained execution.",
                a -> reporting("Execution evidence.", Map.of("executionId", a.get("executionId"), "evidence",
                        history.get((String) a.get("executionId")).get("evidence"))), "executionId"));
        return List.copyOf(tools);
    }

    private static CallToolResult reporting(String summary, Map<String, Object> data) {
        return CallToolResult.builder().addTextContent(summary).structuredContent(data).build();
    }

    private SyncToolSpecification assertion(String name, String description, String... fields) {
        return tool(name, description, a -> onPage(d -> AssertionsTool.evaluate(name, d, a)), fields);
    }

    private static void requireOne(Map<String, Object> args, String... alternatives) {
        if (Arrays.stream(alternatives).filter(args::containsKey).count() != 1) {
            throw new ToolFailure("INVALID_INPUT", "Supply exactly one of " + String.join(", ", alternatives) + ".");
        }
    }

    private CallToolResult onPage(Function<WebDriver, CallToolResult> action) {
        return sessions.use(driver -> {
            origins.checkContext(driver);
            var result = action.apply(driver);
            origins.checkContext(driver);
            return result;
        });
    }

    private static By selector(Map<String, Object> args) {
        return By.cssSelector((String) args.get("selector"));
    }

    private static WebDriverWait waitFor(WebDriver driver) {
        return new WebDriverWait(driver, ScenarioEngine.limitWait(Duration.ofSeconds(5)));
    }

    private WebElement clickable(WebDriver driver, Map<String, Object> args) {
        var condition = ExpectedConditions.elementToBeClickable(selector(args));
        return waitFor(driver).until(d -> {
            origins.checkContext(d);
            return condition.apply(d);
        });
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static CallToolResult text(String message) {
        return CallToolResult.builder().addTextContent(message).build();
    }

    private static CallToolResult error(String code, String message) {
        return CallToolResult.builder().isError(true).addTextContent(code + ": " + message)
                .structuredContent(Map.of("code", code, "message", message)).build();
    }

    static void validate(JsonSchema schema, Map<String, Object> args) {
        var properties = schema.properties();
        var required = schema.required();
        if (!properties.keySet().containsAll(args.keySet()) || !args.keySet().containsAll(required)) {
            throw new ToolFailure("INVALID_INPUT", "Check required and supported argument names.");
        }
        if (args.containsKey("strategy") && !args.containsKey("selector")) {
            throw new ToolFailure("INVALID_INPUT", "A strategy requires a selector.");
        }
        for (var entry : args.entrySet()) {
            var rule = FIELDS.get(entry.getKey());
            Object value = entry.getValue();
            boolean valid = switch ((String) rule.get("type")) {
                case "object" -> value instanceof Map<?, ?>;
                case "boolean" -> value instanceof Boolean;
                case "integer" -> (value instanceof Integer || value instanceof Long)
                        && ((Number) value).longValue() >= (int) rule.get("minimum")
                        && ((Number) value).longValue() <= (int) rule.get("maximum");
                default -> value instanceof String s && s.length() >= (int) rule.getOrDefault("minLength", 0)
                        && s.length() <= (int) rule.getOrDefault("maxLength", 16)
                        && (!rule.containsKey("enum") || ((List<?>) rule.get("enum")).contains(s));
            };
            if (!valid) throw new ToolFailure("INVALID_INPUT", "Invalid argument: " + entry.getKey());
        }
        if (args.get("text") instanceof String text && text.chars().anyMatch(
                c -> Character.isISOControl(c) || (c >= 0xe000 && c <= 0xf8ff))) {
            throw new ToolFailure("INVALID_INPUT", "Use printable text without control keys.");
        }
    }

    private SyncToolSpecification tool(String name, String description,
            Function<Map<String, Object>, CallToolResult> action, String... fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (String spec : fields) {
            boolean optional = spec.endsWith("?");
            String field = optional ? spec.substring(0, spec.length() - 1) : spec;
            properties.put(field, FIELDS.get(field));
            if (!optional) required.add(field);
        }
        var schema = new JsonSchema("object", properties, required, false, null, null);
        return SyncToolSpecification.builder().tool(Tool.builder().name(name).description(description)
                .inputSchema(schema).build()).callHandler((exchange, request) -> {
            Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
            try {
                validate(schema, args);
                return action.apply(args);
            } catch (ToolFailure failure) {
                return error(failure.code, failure.getMessage());
            } catch (RuntimeException failure) {
                var classified = ToolFailure.browser(failure);
                if (classified.code.equals("BROWSER_ERROR")) {
                    LoggerFactory.getLogger(BrowserTools.class).warn("{} failed with an unclassified browser error", name);
                }
                return error(classified.code, classified.getMessage());
            }
        }).build();
    }
}
