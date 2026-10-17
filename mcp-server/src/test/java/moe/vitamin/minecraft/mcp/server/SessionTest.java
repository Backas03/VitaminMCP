package moe.vitamin.minecraft.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A session whose bots cannot join, against a stand-in agent and a runner that refuses.
 *
 * <p>The refusal is the one a real runner gives a server newer than its protocol support, which is
 * the case this behaviour exists for. Needs Node on the path, as the runner itself does.
 */
class SessionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What the runner prints when the server speaks a protocol it has no backend for. */
    private static final String REFUSAL = "Error: This runner cannot speak protocol 777 (26.3). "
            + "It supports 1.21.11, 26.1.";

    @TempDir
    Path directory;

    private HttpServer agent;
    private Path refusingRunner;

    @BeforeEach
    void startAgent() throws IOException {
        assumeTrue(nodeIsAvailable(), "Node is needed to run the stand-in runner");

        refusingRunner = directory.resolve("runner.mjs");
        Files.writeString(refusingRunner,
                "process.stdout.write('err\\tstartup\\t" + REFUSAL + "\\n');\n"
                        + "process.exit(1);\n");

        agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agent.createContext("/mcp", exchange -> {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody());
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.put("id", request.path("id").asInt());
            ObjectNode result = response.putObject("result");
            if ("tools/list".equals(request.path("method").asText())) {
                result.putArray("tools");
            } else {
                result.putArray("content").addObject()
                        .put("type", "text")
                        .put("text", answer(request.path("params").path("name").asText()));
            }
            byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        agent.start();
    }

    @AfterEach
    void stopAgent() {
        if (agent != null) {
            agent.stop(0);
        }
    }

    @Test
    void aRunnerRefusalLeavesTheSessionOpenForTheAgent() {
        Session session = new Session("127.0.0.1", 25565, agent.getAddress().getPort(), "token",
                false, null, refusingRunner);
        try {
            assertFalse(session.botsAvailable());
            assertTrue(session.botsUnavailable().contains("cannot speak protocol 777"),
                    session.botsUnavailable());
            assertFalse(session.botsUnavailable().contains("\t"),
                    "the protocol framing leaked into the reason: " + session.botsUnavailable());
            assertTrue(session.isRunning(), "a session without bots must not be pruned");
            assertTrue(session.describe().contains("agent only"), session.describe());
            assertEquals("26.3-200-abcdef (MC: 26.3)", session.serverInfo().path("version").asText());

            IllegalStateException refused = assertThrows(IllegalStateException.class, session::bots);
            assertTrue(refused.getMessage().contains("cannot speak protocol 777"), refused.getMessage());
            assertTrue(refused.getMessage().contains("logs_query"), refused.getMessage());
        } finally {
            session.close();
        }
    }

    @Test
    void sessionStartSaysWhyBotsAreUnavailableAndTheAgentToolsStillAnswer() {
        SessionTools tools = new SessionTools();
        try {
            JsonNode started = tools.call("session_start", start());

            assertFalse(started.path("bots").path("available").asBoolean(true), started.toString());
            assertTrue(started.path("bots").path("reason").asText().contains("26.3"),
                    started.toString());
            assertTrue(started.path("minecraftProtocol").isNull(), started.toString());
            assertEquals("Paper", started.path("server").path("implementation").asText());
            assertEquals(1, started.path("sessions").size(), "the session left the roster");

            JsonNode info = tools.call("server_info", MAPPER.createObjectNode());
            assertEquals("Paper", info.path("implementation").asText());

            ObjectNode spawn = MAPPER.createObjectNode().put("name", "Tester");
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> tools.call("bot_spawn", spawn));
            assertTrue(refused.getMessage().contains("Bots are unavailable"), refused.getMessage());
        } finally {
            tools.close();
        }
    }

    @Test
    void aResetThatStillCannotStartBotsKeepsTheSession() {
        SessionTools tools = new SessionTools();
        try {
            tools.call("session_start", start());

            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> tools.call("session_reset", MAPPER.createObjectNode()));
            assertTrue(refused.getMessage().contains("stays open"), refused.getMessage());

            JsonNode info = tools.call("server_info", MAPPER.createObjectNode());
            assertEquals("Paper", info.path("implementation").asText(),
                    "the agent stopped answering after a failed reset");
        } finally {
            tools.close();
        }
    }

    private ObjectNode start() {
        return MAPPER.createObjectNode()
                .put("host", "127.0.0.1")
                .put("port", 25565)
                .put("mcpPort", agent.getAddress().getPort())
                .put("token", "token")
                .put("runnerJar", refusingRunner.toString());
    }

    /** The stand-in agent's answers: a writable 26.3 server with nobody on it. */
    private static String answer(String tool) {
        ObjectNode payload = MAPPER.createObjectNode();
        switch (tool) {
            case "server_info" -> payload
                    .put("implementation", "Paper")
                    .put("version", "26.3-200-abcdef (MC: 26.3)")
                    .put("minecraftVersion", "26.3-R0.1-SNAPSHOT")
                    .put("readOnly", false);
            case "state_query" -> payload.put("online", false);
            default -> payload.put("tool", tool);
        }
        return payload.toString();
    }

    private static boolean nodeIsAvailable() {
        String configured = System.getenv("VITAMINMCP_NODE");
        String node = configured != null && !configured.isBlank()
                ? configured
                : System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "node.exe"
                        : "node";
        try {
            Process probe = new ProcessBuilder(node, "--version").redirectErrorStream(true).start();
            probe.getInputStream().readAllBytes();
            return probe.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
