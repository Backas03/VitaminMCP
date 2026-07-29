package moe.vitamin.minecraft.mcp.agent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import moe.vitamin.minecraft.mcp.agent.core.AgentSettings;

/**
 * Serves MCP over HTTP using the JDK's built-in {@link HttpServer}.
 *
 * <p>No web framework. MCP's streamable HTTP transport is a POST that carries one JSON-RPC
 * message, and answering that needs nothing a servlet container provides — while every
 * dependency added here is another library to relocate and another chance to collide with the
 * server or a plugin sharing the JVM (docs/design.md §7).
 *
 * <p><b>Security.</b> The endpoint reaches server internals, so the defaults assume a
 * production server rather than a test one: loopback only, a token required on every request,
 * and startup refused outright when no token is configured. None of these are relaxed for
 * convenience (docs/design.md §14).
 */
public final class McpHttpServer {

    /** The single MCP endpoint. */
    private static final String ENDPOINT = "/mcp";

    /** Revision implemented here. */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    /**
     * Revisions accepted from a client. Older clients are echoed their own version rather than
     * refused: the surface used here — initialize, tools/list, tools/call — is unchanged across
     * all three.
     */
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS =
            Set.of("2025-06-18", "2025-03-26", "2024-11-05");

    /** Ceiling on a request body. Nothing legitimate here is close to this. */
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;

    private final AgentSettings settings;
    private final AgentTools tools;
    private final ObjectMapper mapper;
    private final Logger logger;
    private final String serverVersion;
    private final byte[] expectedToken;

    private HttpServer server;
    private ExecutorService executor;

    public McpHttpServer(
            AgentSettings settings,
            AgentTools tools,
            ObjectMapper mapper,
            Logger logger,
            String serverVersion) {
        this.settings = settings;
        this.tools = tools;
        this.mapper = mapper;
        this.logger = logger;
        this.serverVersion = serverVersion;
        this.expectedToken = settings.authToken().getBytes(StandardCharsets.UTF_8);
    }

    public void start() throws IOException {
        settings.validate();

        InetSocketAddress address =
                new InetSocketAddress(InetAddress.getByName(settings.bindAddress()), settings.port());
        server = HttpServer.create(address, 0);
        server.createContext(ENDPOINT, this::handle);

        AtomicInteger threadNumber = new AtomicInteger();
        executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "VitaminMCP-http-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.start();

        logger.info("MCP endpoint listening on http://" + settings.bindAddress() + ":"
                + settings.port() + ENDPOINT + (settings.readOnly() ? " (read-only)" : ""));

        if (settings.isExternallyReachable()) {
            logger.warning("The MCP endpoint is bound to " + settings.bindAddress()
                    + ", which is reachable from outside this machine. Anyone with the token can "
                    + "read server internals. Bind to 127.0.0.1 and tunnel instead unless you "
                    + "have a specific reason not to.");
        }
    }

    /** The port actually bound, which differs from the configured one when that was 0. */
    public int boundPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    // --------------------------------------------------------------- routing

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!isAuthorized(exchange)) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
                respond(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }

            String method = exchange.getRequestMethod();
            switch (method) {
                case "POST" -> handlePost(exchange);
                // Nothing is pushed to the client, so there is no stream to open.
                case "GET" -> respond(exchange, 405, "{\"error\":\"this endpoint accepts POST only\"}");
                // Sessions are not held, so a termination request is already satisfied.
                case "DELETE" -> respond(exchange, 204, "");
                default -> respond(exchange, 405, "{\"error\":\"unsupported method\"}");
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Unhandled error serving an MCP request", e);
            safelyRespond(exchange, 500, "{\"error\":\"internal error\"}");
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        byte[] body = readBody(exchange);
        if (body == null) {
            respond(exchange, 413, "{\"error\":\"request too large\"}");
            return;
        }

        JsonNode payload;
        try {
            payload = mapper.readTree(body);
        } catch (IOException e) {
            writeJson(exchange, 400, JsonRpc.error(null, JsonRpc.PARSE_ERROR, "Malformed JSON"));
            return;
        }

        if (payload == null || !payload.isObject()) {
            writeJson(exchange, 400, JsonRpc.error(null, JsonRpc.INVALID_REQUEST,
                    payload != null && payload.isArray()
                            ? "Batched requests are not supported by this protocol revision"
                            : "Expected a JSON-RPC object"));
            return;
        }

        JsonRpc.Request request = new JsonRpc.Request(
                payload.has("id") ? payload.get("id") : null,
                payload.path("method").asText(""),
                payload.has("params") ? payload.get("params") : mapper.createObjectNode());

        ObjectNode response = dispatch(request);

        if (response == null) {
            // A notification. Acknowledged, with no body, per JSON-RPC.
            respond(exchange, 202, "");
            return;
        }
        writeJson(exchange, 200, response);
    }

    /** @return the response, or {@code null} when the request was a notification */
    private ObjectNode dispatch(JsonRpc.Request request) {
        if (request.method().isEmpty()) {
            return JsonRpc.error(request.id(), JsonRpc.INVALID_REQUEST, "Missing 'method'");
        }

        // Notifications never get a reply, including ones we do not recognise.
        if (request.isNotification()) {
            return null;
        }

        try {
            return switch (request.method()) {
                case "initialize" -> JsonRpc.success(request.id(), initialize(request.params()));
                case "ping" -> JsonRpc.success(request.id(), mapper.createObjectNode());
                case "tools/list" -> {
                    ObjectNode result = mapper.createObjectNode();
                    result.set("tools", tools.listTools());
                    yield JsonRpc.success(request.id(), result);
                }
                case "tools/call" -> JsonRpc.success(request.id(), callTool(request.params()));
                default -> JsonRpc.error(request.id(), JsonRpc.METHOD_NOT_FOUND,
                        "Unknown method: " + request.method());
            };
        } catch (IllegalArgumentException e) {
            return JsonRpc.error(request.id(), JsonRpc.INVALID_PARAMS, String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Error handling " + request.method(), e);
            return JsonRpc.error(request.id(), JsonRpc.INTERNAL_ERROR, String.valueOf(e.getMessage()));
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText(PROTOCOL_VERSION);
        String negotiated =
                SUPPORTED_PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSION;

        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", negotiated);

        // Only tools. No resources, prompts, sampling or logging capabilities are claimed,
        // because none are implemented and advertising them invites calls that fail.
        result.putObject("capabilities").putObject("tools");

        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "VitaminMCP");
        info.put("version", serverVersion);

        result.put("instructions",
                "Observability for a running Minecraft server. Call server_info first to see "
                        + "what this server is and how much has been captured. For events, call "
                        + "events_summary before events_query — the summary is small no matter "
                        + "how busy the server is and tells you which types to ask for. Every "
                        + "query response carries 'truncated' (page again with nextCursor) and "
                        + "'dropped' (records lost to buffer overflow and gone for good); a "
                        + "non-zero 'dropped' means what you are seeing is incomplete.");
        return result;
    }

    /**
     * Runs a tool and wraps the outcome as MCP content.
     *
     * <p>A tool that fails comes back as a normal result carrying {@code isError}, not as a
     * JSON-RPC error. That is the MCP convention and it matters: a transport error is invisible
     * to the model, whereas "no exception is recorded under that hash" is something it can read
     * and act on.
     */
    private ObjectNode callTool(JsonNode params) {
        String name = params.path("name").asText("");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Missing tool name");
        }

        ObjectNode result = mapper.createObjectNode();
        try {
            JsonNode payload = tools.call(name, params.get("arguments"));
            result.putArray("content")
                    .addObject()
                    .put("type", "text")
                    .put("text", writeText(payload));
            result.put("isError", false);
        } catch (AgentTools.ToolException e) {
            result.putArray("content")
                    .addObject()
                    .put("type", "text")
                    .put("text", e.getMessage());
            result.put("isError", true);
        }
        return result;
    }

    private String writeText(JsonNode payload) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return payload.toString();
        }
    }

    // ------------------------------------------------------------------ auth

    /**
     * Checks the bearer token.
     *
     * <p>Compared with {@link MessageDigest#isEqual} rather than {@link String#equals}, which
     * returns as soon as two bytes differ and so leaks the token a character at a time to
     * anyone able to time the responses.
     */
    private boolean isAuthorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        byte[] presented = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presented, expectedToken);
    }

    // ------------------------------------------------------------------- i/o

    /** @return the body, or {@code null} if it exceeded {@link #MAX_REQUEST_BYTES} */
    private static byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            byte[] body = input.readNBytes(MAX_REQUEST_BYTES + 1);
            return body.length > MAX_REQUEST_BYTES ? null : body;
        }
    }

    private void writeJson(HttpExchange exchange, int status, ObjectNode body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        respond(exchange, status, body.toString());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        if (exchange.getResponseHeaders().getFirst("Content-Type") == null) {
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void safelyRespond(HttpExchange exchange, int status, String body) {
        try {
            respond(exchange, status, body);
        } catch (IOException | RuntimeException e) {
            logger.log(Level.FINE, "Could not write the error response", e);
        }
    }
}
