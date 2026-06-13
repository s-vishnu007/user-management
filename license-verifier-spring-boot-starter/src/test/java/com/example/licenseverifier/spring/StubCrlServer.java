package com.example.licenseverifier.spring;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A tiny in-process HTTP server that serves whatever CRL body / status code is currently
 * configured, so the starter's {@link CrlRevocationChecker} can be driven over a real HTTP fetch.
 * The served body and status can be swapped between fetches to simulate refresh failures, replays
 * and rollbacks. Tracks the number of {@code GET} hits.
 */
final class StubCrlServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>("");
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger hits = new AtomicInteger(0);

    StubCrlServer() {
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start stub CRL server", e);
        }
        server.createContext("/crl", exchange -> {
            hits.incrementAndGet();
            int code = status.get();
            byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
            if (code < 200 || code >= 300) {
                exchange.sendResponseHeaders(code, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/jwt");
            exchange.sendResponseHeaders(code, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/crl";
    }

    void serve(String crlBody) {
        this.body.set(crlBody);
        this.status.set(200);
    }

    void serveStatus(int code) {
        this.status.set(code);
    }

    int hits() {
        return hits.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
