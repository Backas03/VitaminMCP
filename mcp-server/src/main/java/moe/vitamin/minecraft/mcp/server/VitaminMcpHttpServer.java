package moe.vitamin.minecraft.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** One loopback HTTP server shared by every local MCP client. */
final class VitaminMcpHttpServer implements AutoCloseable {

    static final int DEFAULT_PORT = 25584;
    static final String SESSION_HEADER = "Mcp-Session-Id";
    static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofHours(6);

    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int requestedPort;
    private final long idleTimeoutNanos;
    private final LongSupplier clock;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final CountDownLatch stopped = new CountDownLatch(1);

    private HttpServer server;
    private ExecutorService executor;
    private ScheduledExecutorService sweeper;

    VitaminMcpHttpServer(int port) {
        this(port, DEFAULT_IDLE_TIMEOUT, System::nanoTime);
    }

    VitaminMcpHttpServer(int port, Duration idleTimeout, LongSupplier clock) {
        this.requestedPort = port;
        this.idleTimeoutNanos = idleTimeout.toNanos();
        this.clock = clock;
    }

    void start() throws IOException {
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), requestedPort), 0);
        server.createContext("/mcp", this::handle);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        sweeper = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "VitaminMCP-http-idle-sessions");
            thread.setDaemon(true);
            return thread;
        });
        sweeper.scheduleWithFixedDelay(this::expireIdleSessionsQuietly, 1, 1, TimeUnit.MINUTES);
        System.err.println("VitaminMCP shared server ready on http://127.0.0.1:"
                + port() + "/mcp");
    }

    int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    int sessionCount() {
        return sessions.size();
    }

    void await() {
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void expireIdleSessions() {
        long now = clock.getAsLong();
        List<Session> expired = new ArrayList<>();
        for (String id : sessions.keySet()) {
            sessions.computeIfPresent(id, (key, session) -> {
                if (session.inFlight.get() > 0 || now - session.lastUsedNanos < idleTimeoutNanos) {
                    return session;
                }
                expired.add(session);
                return null;
            });
        }
        expired.forEach(session -> session.client.close());
    }

    private void expireIdleSessionsQuietly() {
        try {
            expireIdleSessions();
        } catch (RuntimeException e) {
            System.err.println("VitaminMCP could not close an idle session: " + e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            // Browser requests carry Origin. Refusing them prevents a webpage from using the local
            // endpoint through DNS rebinding; native MCP clients do not send this header.
            if (exchange.getRequestHeaders().getFirst("Origin") != null) {
                respond(exchange, 403, "");
                return;
            }

            switch (exchange.getRequestMethod()) {
                case "POST" -> post(exchange);
                case "DELETE" -> delete(exchange);
                default -> respond(exchange, 405, "");
            }
        }
    }

    private void post(HttpExchange exchange) throws IOException {
        byte[] body = readBody(exchange);
        if (body == null) {
            respond(exchange, 413, "");
            return;
        }

        JsonNode request;
        try {
            request = MAPPER.readTree(body);
        } catch (IOException e) {
            writeJson(exchange, 400, error(null, -32700, "Malformed JSON"));
            return;
        }
        if (request == null || !request.isObject()) {
            writeJson(exchange, 400, error(null, -32600, "Expected a JSON-RPC object"));
            return;
        }

        String method = request.path("method").asText("");
        String sessionId = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
        Session session;

        if ("initialize".equals(method)) {
            if (sessionId != null || !request.hasNonNull("id")) {
                writeJson(exchange, 400,
                        error(request.get("id"), -32600, "Invalid initialize request"));
                return;
            }
            sessionId = UUID.randomUUID().toString();
            session = new Session(clock.getAsLong());
            session.inFlight.incrementAndGet();
            sessions.put(sessionId, session);
            exchange.getResponseHeaders().add(SESSION_HEADER, sessionId);
        } else {
            session = sessionId == null ? null : acquire(sessionId);
            if (session == null) {
                respond(exchange, 404, "");
                return;
            }
        }

        ObjectNode response;
        try {
            response = session.client.handle(request);
        } finally {
            session.lastUsedNanos = clock.getAsLong();
            session.inFlight.decrementAndGet();
        }
        if (response == null) {
            respond(exchange, 202, "");
        } else {
            writeJson(exchange, 200, response);
        }
    }

    private Session acquire(String sessionId) {
        return sessions.computeIfPresent(sessionId, (key, session) -> {
            session.inFlight.incrementAndGet();
            session.lastUsedNanos = clock.getAsLong();
            return session;
        });
    }

    private void delete(HttpExchange exchange) throws IOException {
        String sessionId = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
        Session removed = sessionId == null ? null : sessions.remove(sessionId);
        if (removed == null) {
            respond(exchange, 404, "");
            return;
        }
        removed.client.close();
        respond(exchange, 204, "");
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            byte[] body = input.readNBytes(MAX_REQUEST_BYTES + 1);
            return body.length > MAX_REQUEST_BYTES ? null : body;
        }
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? MAPPER.nullNode() : id);
        response.putObject("error").put("code", code).put("message", message);
        return response;
    }

    private static void writeJson(HttpExchange exchange, int status, ObjectNode body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        respond(exchange, status, body.toString());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (sweeper != null) {
            sweeper.shutdownNow();
            sweeper = null;
        }
        sessions.values().forEach(session -> session.client.close());
        sessions.clear();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        stopped.countDown();
    }

    private static final class Session {

        final VitaminMcpServer client = new VitaminMcpServer();
        final AtomicInteger inFlight = new AtomicInteger();
        volatile long lastUsedNanos;

        Session(long now) {
            this.lastUsedNanos = now;
        }
    }
}
