package dev.seleniummcp.engine;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

final class DisallowedOriginFixture {
    private DisallowedOriginFixture() {}

    static HttpServer start() throws IOException {
        // A different port supplies a distinct origin without depending on localhost name resolution.
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = "<!doctype html><title>Disallowed fixture</title>".getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        return server;
    }
}
