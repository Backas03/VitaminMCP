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

    /** RFC 9728 discovery path. */
    private static final String PROTECTED_RESOURCE_METADATA =
            "/.well-known/oauth-protected-resource";

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
    private final BearerTokenVerifier tokens;
    private final ActivityLog activity;

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
        this.tokens = new BearerTokenVerifier(settings.authToken(), settings.oauth(), logger);
        this.activity = new ActivityLog(logger, settings.activityLog());
    }

    public void start() throws IOException {
        settings.validate();

        InetSocketAddress address =
                new InetSocketAddress(InetAddress.getByName(settings.bindAddress()), settings.port());
        server = settings.tls().enabled()
                ? createHttpsServer(address)
                : HttpServer.create(address, 0);
        server.createContext(ENDPOINT, this::handle);

        // RFC 9728. How a client that has no credentials discovers where to get some: it gets
        // a 401 pointing here, reads which authorization server to use, and comes back with a
        // token. Without it the only recovery is a human reading documentation.
        server.createContext(PROTECTED_RESOURCE_METADATA, this::describeProtectedResource);

        AtomicInteger threadNumber = new AtomicInteger();
        executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "VitaminMCP-http-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.start();

        // boundPort() rather than the configured one: a port of 0 means "any free port", and
        // printing the 0 back tells a reader nothing about where to connect.
        logger.info("MCP endpoint listening on " + scheme() + "://" + settings.bindAddress() + ":"
                + boundPort() + ENDPOINT + (settings.readOnly() ? " (read-only)" : ""));

        if (settings.isExternallyReachable()) {
            logger.warning("The MCP endpoint is reachable from outside this machine. Anyone "
                    + "holding the token can read server internals"
                    + (settings.readOnly() ? "" : " and run console commands")
                    + ". Keep the token secret and rotate it if it leaks.");
            if (settings.tls().terminatedUpstream()) {
                logger.warning("tls.terminated-upstream is set, so this agent is serving plain "
                        + "HTTP and trusting whatever is in front of it to terminate TLS. If "
                        + "nothing is, the token is crossing the network in clear text.");
            }
            logConnectionDetails();
        }
    }

    /**
     * Prints what a client needs, in the shape it needs it.
     *
     * <p>Everything here is already knowable — from config.yml, from the keystore, from the
     * machine's own address — but only by collecting it from four places and getting the format
     * right. Connecting to a remote agent was mostly that clerical work, so the agent does it.
     *
     * <p>The fingerprint is the part worth having. A self-signed certificate is otherwise
     * unusable without exporting it, copying it over and building a truststore; pinned by
     * fingerprint it needs nothing installed on the client at all.
     */
    private void logConnectionDetails() {
        StringBuilder block = new StringBuilder("Connect with session_start:")
                .append(System.lineSeparator())
                .append("  \"host\": \"").append(reachableHost()).append("\",")
                .append(" \"mcpPort\": ").append(settings.port()).append(",")
                .append(" \"tls\": \"").append(settings.tls().enabled()).append("\",")
                .append(System.lineSeparator())
                .append("  \"token\": \"").append(settings.authToken()).append("\"");

        certificateFingerprint().ifPresent(fingerprint -> block
                .append(",").append(System.lineSeparator())
                .append("  \"tlsFingerprint\": \"sha256:").append(fingerprint).append("\""));

        logger.info(block.toString());
    }

    /**
     * A host a client could actually dial.
     *
     * <p>{@code 0.0.0.0} means "every interface", which is not an address anything can connect
     * to — printing it verbatim would hand the reader something that cannot work.
     */
    private String reachableHost() {
        if (!"0.0.0.0".equals(settings.bindAddress()) && !"::".equals(settings.bindAddress())) {
            return settings.bindAddress();
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (IOException e) {
            return "<this server's address>";
        }
    }

    /** SHA-256 of the certificate being served, as lowercase hex, if TLS is on. */
    private java.util.Optional<String> certificateFingerprint() {
        if (!settings.tls().enabled()) {
            return java.util.Optional.empty();
        }
        try {
            char[] password = settings.tls().keystorePassword().toCharArray();
            java.security.KeyStore keystore = java.security.KeyStore.getInstance("PKCS12");
            try (var in = java.nio.file.Files.newInputStream(
                    java.nio.file.Path.of(settings.tls().keystore()))) {
                keystore.load(in, password);
            }
            java.util.Enumeration<String> aliases = keystore.aliases();
            while (aliases.hasMoreElements()) {
                java.security.cert.Certificate certificate =
                        keystore.getCertificate(aliases.nextElement());
                if (certificate != null) {
                    byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                            .digest(certificate.getEncoded());
                    StringBuilder hex = new StringBuilder(digest.length * 2);
                    for (byte b : digest) {
                        hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                                .append(Character.forDigit(b & 0xF, 16));
                    }
                    return java.util.Optional.of(hex.toString());
                }
            }
            return java.util.Optional.empty();
        } catch (java.security.GeneralSecurityException | IOException e) {
            // Not fatal: the server is already listening, and a missing fingerprint costs the
            // reader convenience rather than correctness.
            logger.warning("Could not read the certificate to print its fingerprint: " + e);
            return java.util.Optional.empty();
        }
    }

    /**
     * An HTTPS server backed by the configured keystore.
     *
     * <p>The JDK's own HttpsServer, for the same reason the plain one is used: MCP over HTTP is
     * a POST carrying one JSON-RPC message, and nothing a servlet container adds is needed to
     * answer it (docs/design.md §7).
     */
    private HttpServer createHttpsServer(InetSocketAddress address) throws IOException {
        try {
            char[] password = settings.tls().keystorePassword().toCharArray();
            java.security.KeyStore keystore = java.security.KeyStore.getInstance("PKCS12");
            try (var in = java.nio.file.Files.newInputStream(
                    java.nio.file.Path.of(settings.tls().keystore()))) {
                keystore.load(in, password);
            }

            var keyManagers = javax.net.ssl.KeyManagerFactory.getInstance(
                    javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keystore, password);

            javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLS");
            ssl.init(keyManagers.getKeyManagers(), null, null);

            com.sun.net.httpserver.HttpsServer https =
                    com.sun.net.httpserver.HttpsServer.create(address, 0);
            https.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(ssl));
            return https;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("Could not load the TLS keystore at "
                    + settings.tls().keystore() + ": " + e.getMessage(), e);
        }
    }

    /** The scheme this endpoint is reachable on, for messages and metadata. */
    private String scheme() {
        return settings.tls().enabled() ? "https" : "http";
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
        String client = clientOf(exchange);
        try (exchange) {
            String refusal = tokens.refuse(exchange.getRequestHeaders().getFirst("Authorization"));
            if (refusal != null) {
                activity.refused(client, refusal);
                // Points at the metadata document, which is how a client discovers where to
                // obtain a token rather than simply failing.
                exchange.getResponseHeaders().add("WWW-Authenticate",
                        "Bearer error=\"invalid_token\", error_description=\"" + refusal
                                + "\", resource_metadata=\"" + scheme() + "://" + settings.bindAddress()
                                + ":" + boundPort() + PROTECTED_RESOURCE_METADATA + "\"");
                respond(exchange, 401,
                        "{\"error\":\"unauthorized\",\"reason\":\"" + refusal + "\"}");
                return;
            }

            String method = exchange.getRequestMethod();
            switch (method) {
                case "POST" -> handlePost(exchange, client);
                // Nothing is pushed to the client, so there is no stream to open.
                case "GET" -> {
                    activity.malformed(client, "GET, but this endpoint accepts POST only");
                    respond(exchange, 405, "{\"error\":\"this endpoint accepts POST only\"}");
                }
                // Sessions are not held, so a termination request is already satisfied.
                case "DELETE" -> respond(exchange, 204, "");
                default -> {
                    activity.malformed(client, "unsupported HTTP method " + method);
                    respond(exchange, 405, "{\"error\":\"unsupported method\"}");
                }
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Unhandled error serving an MCP request from " + client, e);
            safelyRespond(exchange, 500, "{\"error\":\"internal error\"}");
        }
    }

    private void handlePost(HttpExchange exchange, String client) throws IOException {
        byte[] body = readBody(exchange);
        if (body == null) {
            activity.malformed(client, "the request body exceeded " + MAX_REQUEST_BYTES + " bytes");
            respond(exchange, 413, "{\"error\":\"request too large\"}");
            return;
        }

        JsonNode payload;
        try {
            payload = mapper.readTree(body);
        } catch (IOException e) {
            activity.malformed(client, "malformed JSON");
            writeJson(exchange, 400, JsonRpc.error(null, JsonRpc.PARSE_ERROR, "Malformed JSON"));
            return;
        }

        if (payload == null || !payload.isObject()) {
            String problem = payload != null && payload.isArray()
                    ? "Batched requests are not supported by this protocol revision"
                    : "Expected a JSON-RPC object";
            activity.malformed(client, problem);
            writeJson(exchange, 400, JsonRpc.error(null, JsonRpc.INVALID_REQUEST, problem));
            return;
        }

        JsonRpc.Request request = new JsonRpc.Request(
                payload.has("id") ? payload.get("id") : null,
                payload.path("method").asText(""),
                payload.has("params") ? payload.get("params") : mapper.createObjectNode());

        if (request.isNotification()) {
            // No reply is coming, so there is no outcome to pair with an arrival line.
            activity.notification(client, request.method());
            dispatch(request);
            // Acknowledged, with no body, per JSON-RPC.
            respond(exchange, 202, "");
            return;
        }

        ActivityLog.Call call = begin(client, request);
        ObjectNode response = dispatch(request);
        record(call, response);

        if (response == null) {
            respond(exchange, 202, "");
            return;
        }
        writeJson(exchange, 200, response);
    }

    // -------------------------------------------------------------- activity

    /** The caller's address, which is what distinguishes a local client from a remote one. */
    private static String clientOf(HttpExchange exchange) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        return remote == null
                ? "unknown"
                : remote.getAddress().getHostAddress() + ":" + remote.getPort();
    }

    /**
     * Opens the console record for a call.
     *
     * <p>{@code tools/call} is unwrapped so the line names the tool and its arguments rather
     * than the envelope carrying them — "tools/call" on its own says nothing about what the
     * server was asked to do.
     */
    private ActivityLog.Call begin(String client, JsonRpc.Request request) {
        boolean isToolCall = "tools/call".equals(request.method());
        String toolName = isToolCall ? request.params().path("name").asText("") : null;
        JsonNode arguments = isToolCall ? request.params().get("arguments") : request.params();
        // State-changing tools are logged even when activity logging is turned off: this is the
        // record of the agent altering the server, and it is not noise anyone can opt out of.
        return activity.begin(
                client, request.method(), toolName, arguments, tools.changesState(toolName));
    }

    /**
     * Closes that record with whatever the caller was sent.
     *
     * <p>A failing tool comes back as a normal result carrying {@code isError} (see
     * {@link #callTool}), so reading the outcome means looking inside the result rather than
     * only at the JSON-RPC envelope.
     */
    private static void record(ActivityLog.Call call, ObjectNode response) {
        if (response == null) {
            call.succeeded(null);
            return;
        }
        JsonNode error = response.get("error");
        if (error != null) {
            call.failed(error.path("message").asText(""));
            return;
        }

        JsonNode result = response.path("result");
        String text = result.path("content").path(0).path("text").asText(null);
        if (result.path("isError").asBoolean()) {
            call.failed(text == null ? result.toString() : text);
            return;
        }
        // The tool's own payload where there is one, so the line shows the data the caller got
        // and not the MCP content wrapper around it.
        call.succeeded(text == null ? result.toString() : text);
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
     * Serves the metadata that tells a client how to authenticate (RFC 9728).
     *
     * <p>Unauthenticated on purpose — this is the document explaining how to authenticate, so
     * requiring a token to read it would be circular.
     */
    private void describeProtectedResource(HttpExchange exchange) throws IOException {
        try (exchange) {
            ObjectNode metadata = mapper.createObjectNode();
            metadata.put("resource", settings.oauth().resourceUrl().isEmpty()
                    ? scheme() + "://" + settings.bindAddress() + ":" + boundPort() + ENDPOINT
                    : settings.oauth().resourceUrl());

            if (settings.oauth().enabled()) {
                metadata.putArray("authorization_servers").add(settings.oauth().issuer());
                if (!settings.oauth().requiredScopes().isEmpty()) {
                    var scopes = metadata.putArray("scopes_supported");
                    settings.oauth().requiredScopes().forEach(scopes::add);
                }
            }
            metadata.putArray("bearer_methods_supported").add("header");

            exchange.getResponseHeaders().add("Content-Type", "application/json");
            respond(exchange, 200, metadata.toString());
        }
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
