package dev.seleniummcp.collection.management;

import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.net.httpserver.HttpServer;
import dev.seleniummcp.api.ApiTools;
import dev.seleniummcp.collection.ApiCollectionTools;
import dev.seleniummcp.reporting.ExecutionHistory;
import dev.seleniummcp.support.ToolFailure;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirFactory;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import static org.junit.jupiter.api.Assertions.*;

class SavedCollectionTest {
    @TempDir(factory = PrivateTempFactory.class) Path temporary;
    private Path root;
    private SecureFiles files;
    private SavedCollectionStore store;

    public static final class PrivateTempFactory implements TempDirFactory {
        @Override public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context) throws IOException {
            // Windows' shared Temp can grant Modify to sandbox accounts, which the store intentionally rejects.
            return com.sun.jna.Platform.isWindows()
                    ? Files.createTempDirectory(Path.of(System.getenv("LOCALAPPDATA")), "selenium-saved-test-")
                    : Files.createTempDirectory("selenium-saved-test-");
        }
    }

    @BeforeEach void setup() throws Exception {
        root = temporary.resolve("saved");
        if (com.sun.jna.Platform.isWindows()) {
            windowsDirectory(root, "");
        } else {
            Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        files = new SecureFiles();
        store = new SavedCollectionStore(root, files);
    }

    @Test void persistsDefaultsAndRecoversOnlyVerifiedStages() throws IOException {
        var saved = store.save(SavedDefinition.create(definition("health")));
        var stage = root.resolve(".pending-" + UUID.randomUUID() + ".tmp");
        files.createPrivateFile(stage);
        Files.writeString(stage, "{incomplete");
        var reopened = new SavedCollectionStore(root, new SecureFiles());
        reopened.recover();
        assertFalse(Files.exists(stage));
        assertEquals(saved, reopened.get(saved.id()));
        assertEquals(List.of(saved), reopened.list(10));
        assertTrue(saved.stopOnFailure());
        assertEquals(60, saved.timeoutSeconds());
    }

    @Test void keepsUnknownFilesAndRejectsCorruptRecords() throws IOException {
        var saved = store.save(SavedDefinition.create(definition("health")));
        var committed = root.resolve(saved.id() + ".json");
        Files.writeString(committed, " ".repeat(65537));
        assertCode("STORE_CORRUPT", () -> store.get(saved.id()));
        var unknown = root.resolve("operator-file.txt");
        Files.writeString(unknown, "keep");
        assertCode("STORE_UNAVAILABLE", store::recover);
        assertEquals("keep", Files.readString(unknown));
        assertTrue(Files.exists(committed));
    }

    @Test void rejectsHardLinkedSavedRecords() throws IOException {
        var saved = store.save(SavedDefinition.create(definition("health")));
        Path original = root.resolve(saved.id() + ".json");
        Path alias = Files.createLink(temporary.resolve("alias.json"), original);
        assertCode("STORE_CORRUPT", () -> store.get(saved.id()));
        Files.delete(alias);
        assertEquals(saved, store.get(saved.id()));
    }

    @Test void enforcesQuotaAndSerializesCompetingWriters() throws IOException {
        store.recover();
        try (var file = FileChannel.open(root.resolve(".collections.lock"), StandardOpenOption.WRITE);
             var ignored = file.lock()) {
            assertCode("STORE_BUSY", () -> new SavedCollectionStore(root, files).save(
                    SavedDefinition.create(definition("health"))));
        }
        for (int i = 0; i < 32; i++) store.save(SavedDefinition.create(definition("health")));
        assertEquals(32, store.list(32).size());
        assertCode("STORE_LIMIT", () -> store.save(SavedDefinition.create(definition("health"))));
    }

    @Test void locksAcrossIndependentJavaProcesses() throws Exception {
        store.recover();
        String executable = Path.of(System.getProperty("java.home"), "bin",
                com.sun.jna.Platform.isWindows() ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var process = new ProcessBuilder(executable, "-cp", classpath, LockHolder.class.getName(),
                root.resolve(".collections.lock").toString()).start();
        try {
            var ready = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return process.getInputStream().read(); }
                catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            });
            assertEquals((int) 'L', ready.get(15, TimeUnit.SECONDS));
            assertCode("STORE_BUSY", () -> store.save(SavedDefinition.create(definition("health"))));
            process.getOutputStream().close();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertEquals(0, store.list(32).size());
            var saved = store.save(SavedDefinition.create(definition("health")));
            assertEquals(saved, store.get(saved.id()));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static final class LockHolder {
        public static void main(String[] args) throws IOException {
            try (var file = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
                 var lock = file.lock()) {
                System.out.write('L');
                System.out.flush();
                System.in.read();
            }
        }
    }

    @Test void rejectsUnexpectedFieldsAndTraversalIds() {
        assertCode("INVALID_INPUT", () -> store.get("../outside"));
        assertCode("INVALID_INPUT", () -> EnvironmentRegistry.parse(config(Map.of("dev", Map.of(
                "origin", "http://127.0.0.1:9000", "routes", Map.of("escape", Map.of("method", "GET", "path", "/../else")))))));
        assertCode("INVALID_INPUT", () -> EnvironmentRegistry.parse(config(Map.of("dev", Map.of(
                "origin", "http://127.0.0.1:9000", "routes", Map.of("url", Map.of("method", "GET", "path", "//else")))))));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void rejectsRealJunctionAndOverbroadWindowsAcl() throws Exception {
        var junction = temporary.resolve("junction");
        var process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), root.toString()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Junction setup timed out");
        assertEquals(0, process.exitValue());
        try {
            assertCode("STORE_UNAVAILABLE", () -> new SavedCollectionStore(junction, files).recover());
        } finally {
            Files.delete(junction);
        }
        var broad = temporary.resolve("broad");
        windowsDirectory(broad, "(A;;FR;;;WD)");
        assertCode("STORE_UNAVAILABLE", () -> new SavedCollectionStore(broad, files).recover());
    }

    @Test @EnabledOnOs(OS.LINUX)
    void rejectsSymlinkAndGroupPermissions() throws IOException {
        var alias = Files.createSymbolicLink(temporary.resolve("alias"), root);
        assertCode("STORE_UNAVAILABLE", () -> new SavedCollectionStore(alias, files).recover());
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-x---"));
        assertCode("STORE_UNAVAILABLE", store::recover);
    }

    @Test void policyAndAllSecretsAreCheckedBeforeDispatch() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        var calls = new AtomicInteger();
        server.createContext("/", exchange -> {
            try (exchange) {
                calls.incrementAndGet();
                boolean read = exchange.getRequestMethod().equals("GET");
                boolean credential = "Bearer fixture-only".equals(exchange.getRequestHeaders().getFirst("Authorization"));
                boolean body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).equals("{}");
                byte[] response = "{synthetic-response-marker".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(read || (credential && body) ? 200 : 403, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try (var api = new ApiTools(origin, true)) {
            var history = new ExecutionHistory(temporary.resolve("reports"), 32);
            var collections = new ApiCollectionTools(api, history);
            var registry = EnvironmentRegistry.parse(config(Map.of(
                    "dev", Map.of("enabled", true, "origin", origin, "routes", Map.of(
                            "health", Map.of("method", "GET", "path", "/health"),
                            "create", Map.of("method", "POST", "path", "/create", "allowMutation", true,
                                    "secretHeaders", Map.of("Authorization", "PHASE_AUTH"), "jsonBodyEnv", "PHASE_BODY"),
                            "blocked", Map.of("method", "POST", "path", "/blocked"))),
                    "staging", Map.of("origin", origin, "routes", Map.of()),
                    "qa", Map.of("enabled", true, "origin", "http://127.0.0.1:1", "routes",
                            Map.of("health", Map.of("method", "GET", "path", "/health"))))));
            var data = store.save(SavedDefinition.create(definition("health", "create")));
            var missing = new SavedCollectionTools(collections, new SavedCollectionPlanner(api, key -> null), registry, store);
            assertEquals(List.of("dev", "qa"), structured(call(missing, "list_api_environments", Map.of())).get("environments"));
            checkError(missing, data, "dev", "SECRET_UNAVAILABLE");
            checkError(missing, data, "staging", "ENVIRONMENT_DISABLED");
            checkError(missing, data, "qa", "API_ORIGIN_DENIED");
            checkError(missing, store.save(SavedDefinition.create(definition("health", "blocked"))), "dev", "ROUTE_NOT_ALLOWED");
            var injected = new SavedCollectionTools(collections, new SavedCollectionPlanner(api, key -> "\r\nfixture"),
                    registry, store);
            checkError(injected, data, "dev", "SECRET_INVALID");
            assertEquals(0, calls.get());
            var ready = new SavedCollectionTools(collections, new SavedCollectionPlanner(api,
                    key -> key.equals("PHASE_AUTH") ? "Bearer fixture-only" : "{}"), registry, store);
            var result = call(ready, "execute_saved_api_collection", Map.of("collectionId", data.id(), "environmentId", "dev"));
            assertNotEquals(Boolean.TRUE, result.isError(), result::toString);
            var summary = structured(result);
            assertEquals("PASS", summary.get("status"));
            assertEquals(2, summary.get("passed"));
            assertEquals(2, calls.get());
            String report = history.get((String) summary.get("executionId")).toString();
            String definition = Files.readString(root.resolve(data.id() + ".json"));
            for (String candidate : List.of(report, definition, result.toString())) {
                for (String sensitive : List.of("PHASE_AUTH", "PHASE_BODY", "fixture-only", "synthetic-response-marker", origin)) {
                    assertFalse(candidate.contains(sensitive));
                }
            }
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, Object> config(Map<String, ?> profiles) {
        return Map.of("schemaVersion", 1, "profiles", profiles);
    }

    private static Map<String, Object> definition(String... routes) {
        return Map.of("name", "Fixture", "requests", java.util.Arrays.stream(routes)
                .map(route -> Map.of("id", route, "name", route, "route", route, "expectedStatus", 200)).toList());
    }

    private static void checkError(SavedCollectionTools tools, SavedDefinition definition, String env, String code) {
        var result = call(tools, "execute_saved_api_collection", Map.of("collectionId", definition.id(), "environmentId", env));
        assertEquals(Boolean.TRUE, result.isError());
        assertEquals(code, structured(result).get("code"));
    }

    private static CallToolResult call(SavedCollectionTools tools, String name, Map<String, Object> args) {
        return tools.specifications().stream().filter(spec -> name.equals(spec.tool().name())).findFirst().orElseThrow()
                .callHandler().apply(null, new CallToolRequest(name, args));
    }

    private static Map<?, ?> structured(CallToolResult result) { return (Map<?, ?>) result.structuredContent(); }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code, assertThrows(ToolFailure.class, operation).code);
    }

    public interface DirectoryFixture extends StdCallLibrary {
        boolean CreateDirectoryW(WString path, WinBase.SECURITY_ATTRIBUTES attributes);
    }

    private static void windowsDirectory(Path dir, String extraAce) {
        var token = new WinNT.HANDLEByReference();
        assertTrue(Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token));
        String sid;
        try { sid = Advapi32Util.getTokenAccount(token.getValue()).sidString; }
        finally { Kernel32.INSTANCE.CloseHandle(token.getValue()); }
        var descriptor = new PointerByReference();
        var security = Native.load("Advapi32", SecureFiles.Security.class);
        String sddl = "O:" + sid + "D:P(A;OICI;FA;;;" + sid + ")(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)" + extraAce;
        assertTrue(security.ConvertStringSecurityDescriptorToSecurityDescriptorW(new WString(sddl), 1, descriptor, null));
        try {
            var attributes = new WinBase.SECURITY_ATTRIBUTES();
            attributes.lpSecurityDescriptor = descriptor.getValue();
            assertTrue(Native.load("Kernel32", DirectoryFixture.class).CreateDirectoryW(new WString(dir.toString()), attributes));
        } finally {
            Kernel32.INSTANCE.LocalFree(descriptor.getValue());
        }
    }
}
