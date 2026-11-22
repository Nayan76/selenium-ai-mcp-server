package dev.seleniummcp;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.openqa.selenium.*;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

final class AdvancedTools {
    private static final By INTERACTIVE = By.cssSelector(String.join(",",
            "button", "a[href]", "input:not([type=hidden])", "textarea", "select", "summary",
            "[tabindex]:not([tabindex='-1'])", "[contenteditable]:not([contenteditable=false])",
            "[role=button]", "[role=link]", "[role=checkbox]", "[role=radio]", "[role=combobox]",
            "[role=textbox]", "[role=switch]", "[role=tab]", "[role=menuitem]", "[role=slider]"));
    private final Origins origins;

    AdvancedTools(Origins origins) {
        this.origins = origins;
    }

    private static CallToolResult result(String summary, Map<String, ?> data) {
        return CallToolResult.builder().addTextContent(summary).structuredContent(data).build();
    }

    private static CallToolResult done(String message) {
        return CallToolResult.builder().addTextContent(message).build();
    }

    static By locator(Map<String, Object> args) {
        String selector = (String) args.get("selector");
        if (selector.isBlank()) throw new ToolFailure("INVALID_SELECTOR", "Supply a nonblank locator.");
        return switch ((String) args.getOrDefault("strategy", "css")) {
            case "css" -> By.cssSelector(selector);
            case "id" -> By.id(selector);
            case "name" -> By.name(selector);
            default -> throw new ToolFailure("INVALID_INPUT", "Unsupported locator strategy.");
        };
    }

    private <T> T await(WebDriver driver, int seconds, ExpectedCondition<T> condition) {
        return new WebDriverWait(driver, ScenarioEngine.limitWait(Duration.ofSeconds(seconds)))
                .ignoring(StaleElementReferenceException.class).until(d -> {
                    origins.checkContext(d);
                    return condition.apply(d);
                });
    }

    CallToolResult waitForElement(WebDriver driver, Map<String, Object> args) {
        By by = locator(args);
        ExpectedCondition<?> condition = switch ((String) args.get("condition")) {
            case "presence" -> ExpectedConditions.presenceOfElementLocated(by);
            case "visibility" -> ExpectedConditions.visibilityOfElementLocated(by);
            case "clickability" -> ExpectedConditions.elementToBeClickable(by);
            case "absence" -> d -> d.findElements(by).isEmpty();
            case "invisibility" -> d -> d.findElements(by).stream().noneMatch(WebElement::isDisplayed);
            default -> throw new ToolFailure("INVALID_INPUT", "Unsupported wait condition.");
        };
        await(driver, ((Number) args.getOrDefault("timeoutSeconds", 5)).intValue(), condition);
        return done("Condition satisfied.");
    }

    CallToolResult discover(WebDriver driver) {
        List<WebElement> candidates = driver.findElements(INTERACTIVE);
        var rows = new ArrayList<Map<String, Object>>();
        int stale = 0;
        for (WebElement element : candidates.stream().limit(50).toList()) {
            try {
                rows.add(describe(driver, element));
            } catch (StaleElementReferenceException changed) {
                stale++;
            }
        }
        return result("Discovered " + candidates.size() + " candidates.", Map.of(
                "total", candidates.size(), "elements", rows, "staleSkipped", stale));
    }

    CallToolResult details(WebDriver driver, Map<String, Object> args) {
        By by = locator(args);
        var data = await(driver, 5, d -> describe(d, d.findElement(by)));
        return result("Element details.", data);
    }

    private static Map<String, Object> describe(WebDriver driver, WebElement element) {
        var data = new LinkedHashMap<String, Object>();
        String tag = element.getTagName();
        data.put("tag", tag);
        data.put("text", tag.equals("textarea") || tag.equals("input") ? "" : bounded(element.getText()));
        data.put("role", bounded(element.getAriaRole()));
        data.put("accessibleLabel", bounded(element.getAccessibleName()));
        for (String attr : List.of("id", "name", "type")) {
            String raw = element.getDomAttribute(attr);
            data.put(attr, bounded(raw));
            if (!attr.equals("type") && !data.containsKey("locator") && raw != null
                    && !raw.isBlank() && raw.length() <= 256) {
                By by = attr.equals("id") ? By.id(raw) : By.name(raw);
                var matches = driver.findElements(by);
                if (matches.size() == 1 && matches.getFirst().equals(element)) {
                    data.put("locator", Map.of("strategy", attr, "selector", raw));
                }
            }
        }
        data.put("visible", element.isDisplayed());
        data.put("enabled", element.isEnabled());
        data.put("selected", element.isSelected());
        return data;
    }

    private static String bounded(String value) {
        return value == null ? "" : value.substring(0, Math.min(256, value.length()));
    }

    CallToolResult select(WebDriver driver, Map<String, Object> args) {
        var element = await(driver, 5, ExpectedConditions.presenceOfElementLocated(locator(args)));
        if (!element.getTagName().equalsIgnoreCase("select")) {
            throw new ToolFailure("UNSUPPORTED_FIELD", "Choose a native HTML select.");
        }
        if (!element.isEnabled()) throw new ToolFailure("DISABLED", "The select is disabled.");
        var select = new Select(element);
        try {
            if (args.containsKey("index")) select.selectByIndex(((Number) args.get("index")).intValue());
            else if (args.containsKey("value")) select.selectByValue((String) args.get("value"));
            else select.selectByVisibleText((String) args.get("visibleText"));
        } catch (org.openqa.selenium.NoSuchElementException missing) {
            throw new ToolFailure("OPTION_NOT_FOUND", "The select has no matching option.");
        } catch (UnsupportedOperationException disabled) {
            throw new ToolFailure("DISABLED", "The option is disabled.");
        }
        return done("Option selected.");
    }

    CallToolResult scroll(WebDriver driver, Map<String, Object> args) {
        var js = (JavascriptExecutor) driver;
        if (args.containsKey("direction")) {
            int direction = args.get("direction").equals("down") ? 1 : -1;
            js.executeScript("window.scrollBy({top: arguments[0] * window.innerHeight, behavior: 'instant'})", direction);
        } else {
            var element = await(driver, 5, ExpectedConditions.visibilityOfElementLocated(locator(args)));
            scrollIntoView(driver, element);
        }
        return done("Scrolled.");
    }

    CallToolResult hover(WebDriver driver, Map<String, Object> args) {
        var element = await(driver, 5, ExpectedConditions.visibilityOfElementLocated(locator(args)));
        scrollIntoView(driver, element);
        new Actions(driver).moveToElement(element).perform();
        return done("Pointer moved.");
    }

    private static void scrollIntoView(WebDriver driver, WebElement element) {
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({block: 'center', behavior: 'instant'})", element);
    }

    CallToolResult defaultDocument(WebDriver driver) {
        driver.switchTo().defaultContent();
        origins.checkContext(driver);
        return done("Default document selected.");
    }

    CallToolResult frame(WebDriver driver, Map<String, Object> args) {
        if (args.containsKey("index")) {
            driver.switchTo().frame(((Number) args.get("index")).intValue());
        } else {
            By by = args.containsKey("id") ? By.id((String) args.get("id"))
                    : args.containsKey("name") ? By.name((String) args.get("name")) : locator(args);
            var frame = await(driver, 5, ExpectedConditions.presenceOfElementLocated(by));
            driver.switchTo().frame(frame);
        }
        try {
            origins.checkContext(driver);
        } catch (RuntimeException denied) {
            driver.switchTo().defaultContent();
            throw denied;
        }
        return done("Child frame selected.");
    }

    CallToolResult window(WebDriver driver, Map<String, Object> args) {
        var handles = driver.getWindowHandles();
        if (args.isEmpty()) return result("Available windows.", Map.of("handles", handles.stream().sorted().toList()));
        String target = (String) args.get("handle");
        if (!handles.contains(target)) throw new ToolFailure("WINDOW_NOT_FOUND", "Choose an available handle.");
        String previous = null;
        try {
            previous = driver.getWindowHandle();
        } catch (NoSuchWindowException closed) {
            // The active window closed. A surviving handle can still be selected.
        }
        driver.switchTo().window(target);
        try {
            origins.checkContext(driver);
        } catch (RuntimeException denied) {
            if (previous != null && handles.contains(previous)) driver.switchTo().window(previous);
            throw denied;
        }
        return done("Window selected.");
    }
}
