package dev.seleniummcp.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.openqa.selenium.WebDriver;

final class AssertionsTool {
    static CallToolResult evaluate(String name, WebDriver driver, Map<String, Object> args) {
        Object expected = args.get("expected");
        Object actual;
        String missing = null;
        switch (name) {
            case "assert_page_title" -> actual = driver.getTitle();
            case "assert_current_url" -> actual = driver.getCurrentUrl();
            default -> {
                var matches = driver.findElements(AdvancedTools.locator(args));
                var first = matches.isEmpty() ? null : matches.getFirst();
                switch (name) {
                    case "assert_element_count" -> {
                        expected = ((Number) args.get("count")).intValue();
                        actual = matches.size();
                    }
                    case "assert_element_visible" -> {
                        expected = args.getOrDefault("visible", true);
                        actual = first != null && first.isDisplayed();
                    }
                    case "assert_element_text" -> actual = first == null ? null : first.getText();
                    case "assert_element_attribute" -> actual = first == null ? null
                            : first.getDomAttribute((String) args.get("attribute"));
                    default -> throw new IllegalArgumentException("Unknown assertion.");
                }
                if (actual == null) missing = first == null ? "Element missing." : "Attribute missing.";
            }
        }
        boolean passed = "contains".equals(args.get("match"))
                ? actual instanceof String s && s.contains((String) expected) : expected.equals(actual);
        var report = new LinkedHashMap<String, Object>();
        report.put("status", passed ? "PASS" : "FAIL");
        report.put("expected", redact(expected));
        report.put("actual", redact(actual));
        String message = passed ? "Expectation satisfied." : missing == null ? "Expectation mismatch." : missing;
        report.put("message", message);
        return CallToolResult.builder().addTextContent(report.get("status") + ": " + message)
                .structuredContent(report).build();
    }

    private static Object redact(Object value) {
        return value instanceof String ? "[redacted]" : value;
    }
}
