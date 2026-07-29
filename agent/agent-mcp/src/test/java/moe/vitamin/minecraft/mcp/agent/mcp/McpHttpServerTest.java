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

    private static AgentSettings settings(String token, String bind) {
        return new AgentSettings(bind, 0, token, true, 1024, 1024, 16, false,
                List.of(), List.of(), List.of(),
                moe.vitamin.minecraft.mcp.agent.core.OAuthSettings.disabled());
    }

    @BeforeEach
    void startServer() throws IOException {
        AgentSettings config = settings(TOKEN, "127.0.0.1");
        AgentTools tools = new AgentTools(
                new StubQueries(), mapper, ResponseBudget.DEFAULT, config.readOnly());
        server = new McpHttpServer(
                config, tools, mapper, Logger.getLogger("test"), "1.0.0-test");
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

    // --------------------------------------------------------------- helpers

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
