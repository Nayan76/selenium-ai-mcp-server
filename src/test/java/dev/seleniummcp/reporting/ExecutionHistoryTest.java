package dev.seleniummcp.reporting;

import dev.seleniummcp.browser.BrowserEvidence;
import dev.seleniummcp.support.ToolFailure;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.logging.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionHistoryTest {
    @TempDir Path root;

    private static Map<String, Object> record(UUID id) {
        return new HashMap<>(Map.of("executionId", id.toString(), "id", "case-1", "browser", "chrome",
                "startedAt", "2026-01-01T00:00:00Z", "endedAt", "2026-01-01T00:00:01Z",
                "durationMs", 1000, "status", "PASS", "plannedSteps", 1, "evidence", List.of(),
                "steps", List.of(Map.of("step", 1, "tool", "assert_page_title", "status", "PASS", "durationMs", 8))));
    }

    @Test void exportsStructuredJsonAndEscapedHtml() throws Exception {
        var history = new ExecutionHistory(root, 2);
        var id = UUID.randomUUID();
        var report = record(id);
        report.put("id", "<script>alert('x')</script>");
        history.complete(report);
        Path json = root.resolve((String) history.export(id.toString(),"json").get("path"));
        assertEquals(report, McpJsonDefaults.getMapper().readValue(Files.readAllBytes(json), Map.class));
        Path html = root.resolve((String) history.export(id.toString(),"html").get("path"));
        String content = Files.readString(html);
        assertTrue(content.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;"));
        assertFalse(content.contains("<script>"));
        assertTrue(content.contains("assert_page_title"));
        assertTrue(content.contains("Content-Security-Policy"));
        assertEquals(html, root.resolve((String) history.export(id.toString(),"html").get("path")));
    }

    @Test void evictsOldReportsAndImagesAndCleansSessionDirectory() throws Exception {
        var history = new ExecutionHistory(root, 1);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var evidence = history.screenshot(first, 2, new byte[]{-119,80,78,71,13,10,26,10});
        history.complete(record(first));
        Path image = root.resolve((String) evidence.get("path"));
        Path json = root.resolve((String) history.export(first.toString(),"json").get("path"));
        assertTrue(Files.exists(image));
        history.complete(record(second));
        assertEquals(second.toString(),history.list().getFirst().get("executionId"));
        assertEquals("EXECUTION_NOT_FOUND",assertThrows(ToolFailure.class, () -> history.get(first.toString())).code);
        assertFalse(Files.exists(image));
        assertFalse(Files.exists(json));
        Path latest = root.resolve((String) history.export(second.toString(),"html").get("path"));
        history.cleanup();
        assertFalse(Files.exists(latest.getParent()));
        assertTrue(Files.isDirectory(root));
    }

    @Test void rejectsTraversalAndOversizeArtifacts() {
        var history = new ExecutionHistory(root, 1);
        var id = UUID.randomUUID();
        history.complete(record(id));
        for (String input : List.of("../outside", root.toString(), UUID.randomUUID().toString())) {
            assertThrows(ToolFailure.class, () -> history.export(input, "json"));
        }
        assertThrows(ToolFailure.class, () -> history.export(id.toString(), "../file"));
        assertThrows(java.io.IOException.class, () -> history.screenshot(id, 1, new byte[4 * 1024 * 1024 + 1]));
    }

    @Test void refusesLinkedArtifactDirectory() throws Exception {
        Path outside = Files.createDirectory(root.resolve("other"));
        Path linked = root.resolve("linked");
        if (System.getProperty("os.name").startsWith("Windows")) {
            var process = new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",linked.toString(),outside.toString()).start();
            assertEquals(0, process.waitFor());
        } else {
            Files.createSymbolicLink(linked, outside);
        }
        try {
            var history = new ExecutionHistory(linked.resolve("artifacts"), 2);
            var id = UUID.randomUUID();
            history.complete(record(id));
            assertEquals("ARTIFACT_UNAVAILABLE", assertThrows(ToolFailure.class, () -> history.export(id.toString(), "html")).code);
            assertFalse(Files.exists(outside.resolve("artifacts")));
        } finally {
            Files.delete(linked);
        }
    }

    @Test void detectsConsoleSupportAndBoundsRedactedEntries() {
        for (boolean supported : List.of(false,true)) {
            Logs logs = new Logs() {
                public Set<String> getAvailableLogTypes() { return supported ? Set.of(LogType.BROWSER) : Set.of(); }
                public LogEntries get(String type) {
                    assertTrue(supported);
                    return new LogEntries(Collections.nCopies(100, new LogEntry(Level.WARNING, 10, "private-marker")));
                }
            };
            var result = BrowserEvidence.consoleEntries(logs, Instant.EPOCH);
            assertEquals(supported ? "COLLECTED" : "UNSUPPORTED", result.get("status"));
            assertFalse(result.toString().contains("private-marker"));
            if (supported) assertEquals(50, ((List<?>) result.get("entries")).size());
        }
    }
}
