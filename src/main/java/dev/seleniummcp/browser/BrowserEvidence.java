package dev.seleniummcp.browser;

import dev.seleniummcp.reporting.ExecutionHistory;
import dev.seleniummcp.security.Origins;
import dev.seleniummcp.support.ToolFailure;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import org.openqa.selenium.*;
import org.openqa.selenium.logging.LogType;

public final class BrowserEvidence {
    private BrowserEvidence() {}

    // WebDriver captures the top-level viewport, including any displayed child documents.
    private static final String DOCUMENTS = """
            const queue = [window.top], urls = [];
            try {
              while (queue.length) {
                const doc = queue.pop();
                urls.push(doc.location.href);
                if (urls.length + queue.length + doc.frames.length > 64) return null;
                for (let i = 0; i < doc.frames.length; i++) queue.push(doc.frames[i]);
              }
              return urls;
            } catch (_) { return null; }
            """;

    public static void capture(BrowserSession sessions, Origins origins, ExecutionHistory history, UUID execution,
                               Map<String, Object> step) {
        try {
            byte[] png = sessions.use(driver -> screenshot(driver, origins));
            step.put("evidence", history.screenshot(execution, (Integer) step.get("step"), png));
        } catch (ToolFailure e) {
            step.put("screenshotStatus", switch (e.code) {
                case "ORIGIN_DENIED", "INVALID_URL", "UNVERIFIED_VIEWPORT" -> "CONTEXT_BLOCKED";
                default -> "UNAVAILABLE";
            });
        } catch (IOException | RuntimeException e) {
            step.put("screenshotStatus", "UNAVAILABLE");
        }
    }

    public static void capture(BrowserSession sessions, Origins origins, ExecutionHistory history, UUID execution,
                               int datasetIndex, String datasetId, Map<String, Object> step) {
        try {
            byte[] png = sessions.use(driver -> screenshot(driver, origins));
            step.put("evidence", history.screenshot(execution, datasetIndex, datasetId, (Integer) step.get("step"), png));
        } catch (ToolFailure e) {
            step.put("screenshotStatus", switch (e.code) {
                case "ORIGIN_DENIED", "INVALID_URL", "UNVERIFIED_VIEWPORT" -> "CONTEXT_BLOCKED";
                default -> "UNAVAILABLE";
            });
        } catch (IOException | RuntimeException e) {
            step.put("screenshotStatus", "UNAVAILABLE");
        }
    }

    public static byte[] screenshot(WebDriver driver, Origins origins) {
        checkViewport(driver, origins);
        byte[] bytes = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
        checkViewport(driver, origins);
        if (bytes.length > ExecutionHistory.MAX_IMAGE) throw new ToolFailure("IMAGE_TOO_LARGE", "Screenshot exceeds 4 MiB.");
        return bytes;
    }

    public static byte[] elementScreenshot(WebDriver driver, WebElement element, Origins origins) {
        checkViewport(driver, origins);
        byte[] bytes = element.getScreenshotAs(OutputType.BYTES);
        checkViewport(driver, origins);
        if (bytes.length > ExecutionHistory.MAX_IMAGE) {
            throw new ToolFailure("IMAGE_TOO_LARGE", "Captured barcode image exceeds 4 MiB.");
        }
        return bytes;
    }

    private static void checkViewport(WebDriver driver, Origins origins) {
        origins.checkContext(driver);
        Object documents = ((JavascriptExecutor) driver).executeScript(DOCUMENTS);
        if (!(documents instanceof List<?> urls) || urls.isEmpty() || urls.size() > 64) {
            throw new ToolFailure("UNVERIFIED_VIEWPORT", "Viewport includes an unverifiable document.");
        }
        for (Object url : urls) origins.check((String) url);
    }

    public static Map<String, Object> consoleEntries(org.openqa.selenium.logging.Logs logs, Instant start) {
        if (!logs.getAvailableLogTypes().contains(LogType.BROWSER)) return Map.of("status", "UNSUPPORTED");
        var entries = new ArrayList<Map<String, Object>>();
        for (var log : logs.get(LogType.BROWSER)) {
            if (log.getTimestamp() < start.toEpochMilli()) continue;
            int level = log.getLevel().intValue();
            entries.add(Map.of("timestamp", Instant.ofEpochMilli(log.getTimestamp()).toString(),
                    "level", level >= Level.SEVERE.intValue() ? "ERROR" : level >= Level.WARNING.intValue() ? "WARN" : "INFO",
                    "message", "[redacted]"));
            if (entries.size() == 50) break;
        }
        return Map.of("status", "COLLECTED", "entries", entries);
    }

    public static Map<String, Object> console(BrowserSession sessions, Origins origins, Instant start) {
        try {
            return sessions.use(driver -> {
                origins.checkContext(driver);
                var report = consoleEntries(driver.manage().logs(), start);
                origins.checkContext(driver);
                return report;
            });
        } catch (UnsupportedCommandException | UnsupportedOperationException e) {
            return Map.of("status", "UNSUPPORTED");
        } catch (RuntimeException e) {
            return Map.of("status", "UNAVAILABLE");
        }
    }
}
