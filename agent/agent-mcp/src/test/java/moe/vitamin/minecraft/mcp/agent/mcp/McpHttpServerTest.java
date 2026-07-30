package moe.vitamin.minecraft.mcp.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import moe.vitamin.minecraft.mcp.agent.core.ActivityLogging;
import moe.vitamin.minecraft.mcp.agent.core.AgentQueries;
import moe.vitamin.minecraft.mcp.agent.core.AgentSettings;
import moe.vitamin.minecraft.mcp.agent.core.SequencedRingBuffer;
import moe.vitamin.minecraft.mcp.contract.EventRecord;
import moe.vitamin.minecraft.mcp.contract.EventsSummary;
import moe.vitamin.minecraft.mcp.contract.ExceptionGroup;
import moe.vitamin.minecraft.mcp.contract.LogEntry;
import moe.vitamin.minecraft.mcp.contract.LogLevel;
import moe.vitamin.minecraft.mcp.contract.ResponseBudget;
import moe.vitamin.minecraft.mcp.contract.ServerInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the real HTTP server over a real socket.
 *
 * <p>Worth the setup: the auth check, the JSON-RPC framing and the refusal to start without a
 * token are all things that only fail at the transport boundary, which a unit test of the tool
 * layer would never reach.
 */
class McpHttpServerTest {

    private static final String TOKEN = "test-token-do-not-reuse";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private McpHttpServer server;
    private URI endpoint;
    private RecordingLogger console;

    private static AgentSettings settings(String token, String bind) {
        return settings(token, bind, true, ActivityLogging.FULL);
    }

    private static AgentSettings settings(
            String token, String bind, boolean readOnly, ActivityLogging activityLog) {
        return new AgentSettings(bind, 0, token, readOnly, 1024, 1024, 16, false,
                List.of(), List.of(), List.of(),
                moe.vitamin.minecraft.mcp.agent.core.OAuthSettings.disabled(),
                moe.vitamin.minecraft.mcp.agent.core.TlsSettings.disabled(),
                activityLog);
    }

    @BeforeEach
    void startServer() throws IOException {
        AgentSettings config = settings(TOKEN, "127.0.0.1");
        AgentTools tools = new AgentTools(
                new StubQueries(), mapper, ResponseBudget.DEFAULT, config.readOnly());
        console = new RecordingLogger();
        server = new McpHttpServer(config, tools, mapper, console.logger(), "1.0.0-test");
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.boundPort() + "/mcp");
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    // ------------------------------------------------------------------ auth

    @Test
    void refusesToStartWithoutAToken() {
        AgentSettings unauthenticated = settings("  ", "127.0.0.1");
        McpHttpServer unstarted = new McpHttpServer(
                unauthenticated,
                new AgentTools(new StubQueries(), mapper, ResponseBudget.DEFAULT, true),
                mapper, Logger.getLogger("test"), "1.0.0-test");

        // docs/design.md §14: a missing token stops startup, it does not warn and continue.
        IllegalStateException thrown = assertThrows(IllegalStateException.class, unstarted::start);
        assertTrue(thrown.getMessage().contains("auth-token"));
    }

    @Test
    void rejectsARequestWithNoToken() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(endpoint)
                        .POST(HttpRequest.BodyPublishers.ofString(rpc(1, "tools/list", "{}")))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
        assertTrue(response.headers().firstValue("WWW-Authenticate").isPresent());
    }

    @Test
    void rejectsAWrongToken() throws Exception {
        assertEquals(401, post("tools/list", "{}", "wrong-token").statusCode());
    }

    @Test
    void rejectsATokenThatIsOnlyAPrefixOfTheRealOne() throws Exception {
        // Guards the constant-time comparison against being replaced by something that treats
        // a prefix as a match.
        assertEquals(401, post("tools/list", "{}", TOKEN.substring(0, 5)).statusCode());
    }

    // -------------------------------------------------------------- protocol

    @Test
    void initializeEchoesASupportedProtocolVersion() throws Exception {
        JsonNode result = resultOf(post("initialize",
                "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{}}", TOKEN));

        assertEquals("2025-03-26", result.get("protocolVersion").asText());
        assertEquals("VitaminMCP", result.get("serverInfo").get("name").asText());
        assertNotNull(result.get("capabilities").get("tools"));
    }

    @Test
    void initializeFallsBackForAnUnknownProtocolVersion() throws Exception {
        JsonNode result = resultOf(post("initialize", "{\"protocolVersion\":\"1999-01-01\"}", TOKEN));

        assertEquals("2025-06-18", result.get("protocolVersion").asText());
    }

    @Test
    void advertisesOnlyTheCapabilitiesItImplements() throws Exception {
        JsonNode capabilities = resultOf(post("initialize", "{}", TOKEN)).get("capabilities");

        // Claiming resources or prompts would invite calls that cannot be served.
        assertEquals(List.of("tools"), iteratorToList(capabilities.fieldNames()));
    }

    @Test
    void listsTools() throws Exception {
        JsonNode result = resultOf(post("tools/list", "{}", TOKEN));

        // Seven read-only tools; command_exec is not among them by default.
        assertEquals(7, result.get("tools").size());
    }

    @Test
    void callsATool() throws Exception {
        JsonNode result = resultOf(post("tools/call",
                "{\"name\":\"server_info\",\"arguments\":{}}", TOKEN));

        assertFalse(result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("Paper"));
    }

    @Test
    void aFailingToolIsContentNotATransportError() throws Exception {
        HttpResponse<String> response = post("tools/call",
                "{\"name\":\"nonexistent_tool\",\"arguments\":{}}", TOKEN);

        // 200 with isError, not a JSON-RPC error: the model has to be able to read what went
        // wrong and try something else.
        assertEquals(200, response.statusCode());
        JsonNode result = resultOf(response);
        assertTrue(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text").asText().contains("nonexistent_tool"));
    }

    @Test
    void respondsToPing() throws Exception {
        assertNotNull(resultOf(post("ping", "{}", TOKEN)));
    }

    @Test
    void notificationsAreAcknowledgedWithoutABody() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(endpoint)
                        .header("Authorization", "Bearer " + TOKEN)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(202, response.statusCode());
        assertTrue(response.body().isEmpty());
    }

    @Test
    void reportsAnUnknownMethod() throws Exception {
        JsonNode error = errorOf(post("tools/nope", "{}", TOKEN));

        assertEquals(JsonRpc.METHOD_NOT_FOUND, error.get("code").asInt());
    }

    @Test
    void reportsMalformedJson() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(endpoint)
                        .header("Authorization", "Bearer " + TOKEN)
                        .POST(HttpRequest.BodyPublishers.ofString("{not json"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(JsonRpc.PARSE_ERROR,
                mapper.readTree(response.body()).get("error").get("code").asInt());
    }

    @Test
    void rejectsBatchedRequests() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(endpoint)
                        .header("Authorization", "Bearer " + TOKEN)
                        .POST(HttpRequest.BodyPublishers.ofString("[{\"jsonrpc\":\"2.0\"}]"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        JsonNode error = mapper.readTree(response.body()).get("error");
        assertEquals(JsonRpc.INVALID_REQUEST, error.get("code").asInt());
        assertTrue(error.get("message").asText().contains("Batched"));
    }

    @Test
    void doesNotServeGet() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(endpoint)
                        .header("Authorization", "Bearer " + TOKEN)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
    }

    // -------------------------------------------------------------- activity

    @Test
    void logsWhatWasCalledAndWhatCameBack() throws Exception {
        post("tools/call", "{\"name\":\"state_query\",\"arguments\":{\"kind\":\"player\","
                + "\"target\":\"Notch\"}}", TOKEN);

        // The arrival line names the tool and the arguments. "tools/call" alone would say
        // nothing about what the server was asked to do.
        String arrival = console.firstContaining("state_query");
        assertTrue(arrival.contains("kind"), arrival);
        assertTrue(arrival.contains("Notch"), arrival);

        // The completion line carries the answer, which is the half an operator cannot
        // reconstruct from the request.
        String completion = console.firstContaining("ok in");
        assertTrue(completion.contains("Notch"), completion);
    }

    @Test
    void logsARefusedTokenButNeverTheTokenItself() throws Exception {
        post("tools/list", "{}", "hunter2-guessed-token");

        String refusal = console.firstContaining("rejected");
        assertEquals(java.util.logging.Level.WARNING, console.levelOf(refusal));
        // A rejected guess is still someone's secret, and console logs get pasted into issues.
        assertFalse(refusal.contains("hunter2-guessed-token"), refusal);
    }

    @Test
    void logsAFailingCallWithTheReason() throws Exception {
        post("tools/call", "{\"name\":\"exceptions_recent\",\"arguments\":{\"hash\":\"nope\"}}",
                TOKEN);

        String completion = console.firstContaining("failed in");
        assertTrue(completion.contains("nope"), completion);
    }

    @Test
    void quietActivityLoggingStillRecordsStateChanges() throws Exception {
        AgentSettings quiet = settings(TOKEN, "127.0.0.1", false, ActivityLogging.OFF);
        RecordingLogger recorder = new RecordingLogger();
        McpHttpServer silent = new McpHttpServer(
                quiet,
                new AgentTools(new StubQueries(), mapper, ResponseBudget.DEFAULT, quiet.readOnly()),
                mapper, recorder.logger(), "1.0.0-test");
        silent.start();

        URI quietEndpoint = URI.create("http://127.0.0.1:" + silent.boundPort() + "/mcp");
        try {
            send(quietEndpoint, "tools/call", "{\"name\":\"server_info\",\"arguments\":{}}");
            assertTrue(recorder.lines().stream().noneMatch(line -> line.contains("server_info")),
                    recorder.lines().toString());

            send(quietEndpoint, "tools/call",
                    "{\"name\":\"command_exec\",\"arguments\":{\"command\":\"say hello\"}}");
        } finally {
            silent.stop();
        }

        // Turning the console quiet is not the same as giving up the record of the agent
        // changing the server, and the record has to include which command it was.
        String logged = recorder.firstContaining("command_exec");
        assertTrue(logged.contains("say hello"), logged);
    }

    // --------------------------------------------------------------- helpers

    /** A {@link Logger} that keeps what was written to it, so the console output can be asserted. */
    private static final class RecordingLogger extends java.util.logging.Handler {

        private static final java.util.concurrent.atomic.AtomicInteger COUNTER =
                new java.util.concurrent.atomic.AtomicInteger();

        private final List<java.util.logging.LogRecord> records =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private final Logger logger =
                Logger.getLogger("vitaminmcp-test-" + COUNTER.incrementAndGet());

        RecordingLogger() {
            logger.setUseParentHandlers(false);
            logger.addHandler(this);
        }

        Logger logger() {
            return logger;
        }

        @Override
        public void publish(java.util.logging.LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        List<String> lines() {
            synchronized (records) {
                return records.stream().map(java.util.logging.LogRecord::getMessage).toList();
            }
        }

        String firstContaining(String fragment) {
            return lines().stream()
                    .filter(line -> line.contains(fragment))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no logged line contained '" + fragment + "'; lines were " + lines()));
        }

        java.util.logging.Level levelOf(String line) {
            synchronized (records) {
                return records.stream()
                        .filter(record -> line.equals(record.getMessage()))
                        .map(java.util.logging.LogRecord::getLevel)
                        .findFirst()
                        .orElseThrow();
            }
        }
    }

    private HttpResponse<String> send(URI target, String method, String params) throws Exception {
        return client.send(
                HttpRequest.newBuilder(target)
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(rpc(1, method, params)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String rpc(int id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method
                + "\",\"params\":" + params + "}";
    }

    private HttpResponse<String> post(String method, String params, String token) throws Exception {
        return client.send(
                HttpRequest.newBuilder(endpoint)
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(rpc(1, method, params)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode resultOf(HttpResponse<String> response) throws IOException {
        JsonNode body = mapper.readTree(response.body());
        assertNotNull(body.get("result"), "expected a result but got: " + response.body());
        return body.get("result");
    }

    private JsonNode errorOf(HttpResponse<String> response) throws IOException {
        JsonNode body = mapper.readTree(response.body());
        assertNotNull(body.get("error"), "expected an error but got: " + response.body());
        return body.get("error");
    }

    private static List<String> iteratorToList(java.util.Iterator<String> iterator) {
        List<String> values = new java.util.ArrayList<>();
        iterator.forEachRemaining(values::add);
        return values;
    }

    @Test
    void anUnauthorisedRequestPointsAtTheMetadataDocument() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(endpoint)
                        .POST(HttpRequest.BodyPublishers.ofString(rpc(1, "tools/list", "{}")))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        String challenge = response.headers().firstValue("WWW-Authenticate").orElse("");
        // RFC 9728: a client with no credentials has to be able to discover where to get some,
        // otherwise the only recovery is a human reading documentation.
        assertTrue(challenge.contains("resource_metadata="), challenge);
        assertTrue(challenge.contains("oauth-protected-resource"), challenge);
    }

    @Test
    void theMetadataDocumentIsReadableWithoutAToken() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + server.boundPort()
                                        + "/.well-known/oauth-protected-resource"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // Requiring a token to read the document that explains how to get a token is circular.
        assertEquals(200, response.statusCode());
        JsonNode metadata = mapper.readTree(response.body());
        assertNotNull(metadata.get("resource"));
        assertEquals("header", metadata.get("bearer_methods_supported").get(0).asText());
    }

        @Test
    void refusesToStartExposedWithoutTransportSecurity() {
        AgentSettings exposed = settings(TOKEN, "0.0.0.0");
        McpHttpServer unstarted = new McpHttpServer(
                exposed,
                new AgentTools(new StubQueries(), mapper, ResponseBudget.DEFAULT, true),
                mapper, Logger.getLogger("test"), "1.0.0-test");

        // The token grants console access; over plain HTTP across a network anything on the
        // path can read it. Refusing is the only response that cannot be ignored.
        IllegalStateException thrown = assertThrows(IllegalStateException.class, unstarted::start);
        assertTrue(thrown.getMessage().contains("clear text"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("tls.enabled"), thrown.getMessage());
    }

    @Test
    void startsExposedWhenAProxyTerminatesTls() throws Exception {
        AgentSettings behindProxy = new AgentSettings(
                "0.0.0.0", 0, TOKEN, true, 1024, 1024, 16, false,
                List.of(), List.of(), List.of(),
                moe.vitamin.minecraft.mcp.agent.core.OAuthSettings.disabled(),
                new moe.vitamin.minecraft.mcp.agent.core.TlsSettings(false, "", "", true),
                ActivityLogging.FULL);

        McpHttpServer proxied = new McpHttpServer(
                behindProxy,
                new AgentTools(new StubQueries(), mapper, ResponseBudget.DEFAULT, true),
                mapper, Logger.getLogger("test"), "1.0.0-test");
        try {
            // An operator asserting a proxy handles TLS is a supported deployment, not a
            // loophole — many servers already run one, and refusing it would push people to
            // the genuinely unsafe option instead.
            proxied.start();
            assertTrue(proxied.boundPort() > 0);
        } finally {
            proxied.stop();
        }
    }

    /** Enough of the query surface to answer the calls these tests make. */
    private static final class StubQueries implements AgentQueries {

        @Override
        public EventsSummary summarize(long from, long to) {
            return new EventsSummary(from, to, 0, 0, List.of());
        }

        @Override
        public SequencedRingBuffer.Batch<EventRecord> queryEvents(
                String cursorToken, Collection<String> types, String player, int limit) {
            return new SequencedRingBuffer.Batch<>(List.of(), 0, 0, true);
        }

        @Override
        public SequencedRingBuffer.Batch<LogEntry> queryLogs(
                String cursorToken, LogLevel minLevel, Pattern pattern, int limit) {
            return new SequencedRingBuffer.Batch<>(List.of(), 0, 0, true);
        }

        @Override
        public List<ExceptionGroup> recentExceptions(int limit) {
            return List.of();
        }

        @Override
        public ExceptionGroup exceptionByHash(String hash) {
            return null;
        }

        @Override
        public ServerInfo serverInfo() {
            return new ServerInfo("Paper", "1.20.4-test", "1.20.4", List.of(20.0), 0, 20, 1L, List.of());
        }

        @Override
        public Map<String, Object> captureStatus() {
            return Map.of("eventsCaptured", 0L);
        }

        @Override
        public String latestEventCursor() {
            return "events:0";
        }

        @Override
        public String latestLogCursor() {
            return "logs:0";
        }

        String lastCommand;
        String lastCommandAs;

        @Override
        public moe.vitamin.minecraft.mcp.contract.PlayerState playerState(
                String name, Collection<String> permissionNodes) {
            return new moe.vitamin.minecraft.mcp.contract.PlayerState(
                    name, "00000000-0000-0000-0000-000000000000", true, "CREATIVE", false,
                    "world", 1, 2, 3, List.of());
        }


        @Override
        public moe.vitamin.minecraft.mcp.contract.WaitResult waitFor(
                moe.vitamin.minecraft.mcp.contract.WaitCondition condition,
                java.time.Duration timeout) {
            return moe.vitamin.minecraft.mcp.contract.WaitResult.matched(
                    condition.describe(), 5L, 1);
        }
        @Override
        public String blockAt(String world, int x, int y, int z) {
            return "world".equals(world) || world == null ? "STONE" : null;
        }

        @Override
        public moe.vitamin.minecraft.mcp.contract.CommandResult executeCommand(
                String command, String asPlayer, java.time.Duration timeout) {
            this.lastCommand = command;
            this.lastCommandAs = asPlayer;
            return new moe.vitamin.minecraft.mcp.contract.CommandResult(
                    command,
                    asPlayer == null
                            ? moe.vitamin.minecraft.mcp.contract.CommandResult.CONSOLE
                            : asPlayer,
                    true, List.of("ok"), 1L);
        }
    }
}
