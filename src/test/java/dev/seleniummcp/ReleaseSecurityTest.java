package dev.seleniummcp;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseSecurityTest {
    @TempDir Path root;
    private static final byte[] IMAGE = {-119,80,78,71,13,10,26,10};

    @Test void oversizedMessageDisconnectsWithoutEchoingContent() throws Exception {
        var done = new CountDownLatch(1);
        try (var in = new ProtocolInput(new ByteArrayInputStream("ok\nprivate-marker".getBytes()), done, 8)) {
            var failure = assertThrows(IOException.class, in::readAllBytes);
            assertFalse(failure.getMessage().contains("private-marker"));
            assertEquals(0, done.getCount());
        }
        var bytes = "1234\r\n5678\n".getBytes();
        try (var in = new ProtocolInput(new ByteArrayInputStream(bytes), new CountDownLatch(1), 4)) {
            assertArrayEquals(bytes, in.readAllBytes());
        }
    }

    @Test void canonicalOriginsOnly() {
        var origins = new Origins("http://127.0.0.1:8000");
        origins.check("http://127.0.0.1:8000/test");
        for (String host : List.of("127.1","0177.0.0.1","0x7f000001","2130706433","[fe80::1%251]")) {
            assertThrows(ToolFailure.class, () -> new Origins("http://" + host));
        }
        assertThrows(ToolFailure.class, () -> new Origins("http://example.com:0"));
    }

    private Path image(ExecutionHistory history, UUID id) throws IOException {
        return root.resolve((String) history.screenshot(id, 1, IMAGE).get("path"));
    }

    @Test void ageSweepProtectsActiveAndUnrelatedContent() throws Exception {
        var history = new ExecutionHistory(root, 5, 1024, Duration.ofHours(1));
        UUID first = UUID.randomUUID(), active = UUID.randomUUID();
        Path expired = image(history, first);
        history.complete(Map.of("executionId", first.toString()));
        Path kept = image(history, active);
        Path userFile = Files.writeString(expired.resolveSibling(first + ".html"), "user-owned");
        var old = FileTime.from(Instant.now().minus(Duration.ofDays(2)));
        Files.setLastModifiedTime(expired, old);
        Files.setLastModifiedTime(kept, old);
        history.maintain();
        assertFalse(Files.exists(expired));
        assertTrue(Files.exists(kept));
        assertEquals("user-owned", Files.readString(userFile));
    }

    @Test void quotaEvictsCompletedContentAndRefusesToEvictActiveBytes() throws Exception {
        var history = new ExecutionHistory(root, 5, IMAGE.length, Duration.ofDays(1));
        UUID finished = UUID.randomUUID();
        Path first = image(history, finished);
        history.complete(Map.of("executionId", finished.toString()));
        Path active = image(history, UUID.randomUUID());
        assertFalse(Files.exists(first));
        assertThrows(IOException.class, () -> image(history, UUID.randomUUID()));
        assertTrue(Files.exists(active));
    }

    @Test void maintenanceCanReclaimEvictedEvidenceAfterLockContention() throws Exception {
        var history = new ExecutionHistory(root, 1, 1024, Duration.ofHours(1));
        var first = UUID.randomUUID();
        Path old = image(history, first);
        history.complete(Map.of("executionId", first.toString()));
        try (var channel = java.nio.channels.FileChannel.open(root.resolve(".selenium-budget.lock"), StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            history.complete(Map.of("executionId", UUID.randomUUID().toString()));
            assertTrue(Files.exists(old));
        }
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(1))));
        history.maintain();
        assertFalse(Files.exists(old));
    }

    @Test void alteredEvidenceAndUnissuedFilenamesSurviveCleanup() throws Exception {
        var history = new ExecutionHistory(root, 1);
        var first = UUID.randomUUID();
        Path path = image(history, first);
        Files.writeString(path, "operator replacement");
        Path other = Files.writeString(path.resolveSibling(first + "-2.png"), "unrelated");
        history.complete(Map.of("executionId", first.toString()));
        history.complete(Map.of("executionId", UUID.randomUUID().toString()));
        assertEquals("operator replacement", Files.readString(path));
        assertEquals("unrelated", Files.readString(other));
    }
}
