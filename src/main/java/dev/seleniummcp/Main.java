package dev.seleniummcp;

import dev.seleniummcp.api.ApiRuntime;
import dev.seleniummcp.api.ApiTools;
import dev.seleniummcp.browser.BrowserSession;
import dev.seleniummcp.collection.ApiCollectionTools;
import dev.seleniummcp.protocol.ProtocolInput;
import dev.seleniummcp.reporting.ExecutionHistory;
import dev.seleniummcp.security.Origins;
import dev.seleniummcp.tools.BrowserTools;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

public final class Main {
    public static void main(String[] args) throws InterruptedException {
        ApiRuntime.configureHeaderParser();
        // The SDK transport logs complete input lines at DEBUG and parse exceptions at ERROR.
        System.setProperty("org.slf4j.simpleLogger.log.io.modelcontextprotocol", "off");
        var protocolOutput = System.out;
        System.setOut(System.err);
        var disconnected = new CountDownLatch(1);
        var input = new ProtocolInput(System.in, disconnected, 4 * 1024 * 1024);
        var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper(), input, protocolOutput);
        var origins = new Origins(System.getenv().getOrDefault("SELENIUM_ALLOWED_ORIGINS", ""));
        var sessions = new BrowserSession();
        var history = ExecutionHistory.configured();
        var collections = ExecutionHistory.configured(ApiCollectionTools.HISTORY_LIMIT);
        var api = new ApiTools(System.getenv().getOrDefault("SELENIUM_API_ALLOWED_ORIGINS", ""),
                Boolean.parseBoolean(System.getenv("SELENIUM_API_ALLOW_LOOPBACK")));
        var maintenance = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("artifact-maintenance").factory());
        maintenance.scheduleWithFixedDelay(history::maintain, 0, 1, TimeUnit.MINUTES);
        maintenance.scheduleWithFixedDelay(collections::maintain, 0, 1, TimeUnit.MINUTES);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            maintenance.shutdown();
            api.close();
            sessions.shutdown();
            if (Boolean.parseBoolean(System.getenv("SELENIUM_CLEANUP_ON_EXIT"))) {
                history.cleanup();
                collections.cleanup();
            }
        }, "browser-cleanup"));
        var server = McpServer.sync(transport).serverInfo("selenium-mcp", "0.4.0")
                .tools(new BrowserTools(sessions, origins, history).specifications())
                .tools(api.specifications())
                .tools(new ApiCollectionTools(api, collections).specifications()).build();
        try {
            disconnected.await();
        } finally {
            api.close();
            sessions.shutdown();
            server.closeGracefully();
        }
    }
}
