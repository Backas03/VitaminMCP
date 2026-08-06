package moe.vitamin.minecraft.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import moe.vitamin.minecraft.mcp.bot.core.BotRunner;
import moe.vitamin.minecraft.mcp.bot.spi.ClientView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** The session's own lifecycle, against a real server. */
@EnabledIfSystemProperty(named = "vitaminmcp.liveServer", matches = "true")
class SessionLiveTest {

    private static final String HOST = System.getProperty("vitaminmcp.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("vitaminmcp.port", 25565);
    private static final int MCP_PORT = Integer.getInteger("vitaminmcp.mcpPort", 25585);
    private static final String TOKEN = System.getProperty("vitaminmcp.token", "");
    private static final Path RUNNER_JAR =
            Path.of(System.getProperty("vitaminmcp.runnerJar", ""));

    /** The screen the server draws on a player, which it keeps nowhere else. */
    @Test
    void theClientViewCarriesTheWholeScreen() throws Exception {
        Session session = new Session(HOST, PORT, MCP_PORT, TOKEN, false, null, RUNNER_JAR);
        try {
            session.bots().spawn("ResetTester");

            Thread.sleep(4000);

            ClientView view =
                    new BotRunner.BotHandle(session.bots(), "ResetTester", 0, 0, 0).inspect();

            System.out.println("messages   = " + view.messages());
            System.out.println("bossBars   = " + view.bossBars());
            System.out.println("scoreboard = " + view.scoreboard());

            assertNotNull(view.messages(), "messages");
            assertNotNull(view.bossBars(), "bossBars");
            view.bossBars().forEach(bar -> {
                assertNotNull(bar.title());
                assertTrue(bar.progress() >= 0.0f && bar.progress() <= 1.0f,
                        "progress out of range: " + bar.progress());
            });
            if (view.scoreboard() != null) {
                assertNotNull(view.scoreboard().title());
                assertNotNull(view.scoreboard().lines());
            }
        } finally {
            session.close();
        }
    }

    /**
     * Reset has to leave the session usable, which is the whole difference between it and close.
     */
    @Test
    void aBotCanStillSpawnAfterReset() throws Exception {
        Session session = new Session(HOST, PORT, MCP_PORT, TOKEN, false, null, RUNNER_JAR);
        try {
            session.bots().spawn("ResetTester");
            assertEquals(1, session.bots().bots().size());

            session.reset();
            assertEquals(0, session.bots().bots().size(), "reset left a bot connected");

            session.bots().spawn("ResetTester");
            assertEquals(1, session.bots().bots().size(),
                    "the runner did not survive reset, so nothing can spawn afterwards");
            assertTrue(session.describe().contains("1 bots"), session.describe());
        } finally {
            session.close();
        }
    }

    /** A second session leaves the first one's bots where they were. */
    @Test
    void aSecondSessionLeavesTheFirstOnesBotsConnected() throws Exception {
        SessionTools tools = new SessionTools();
        try {
            tools.call("session_start", start("lobby"));
            tools.call("bot_spawn", arguments("session", "lobby", "name", "SessionA"));

            tools.call("session_start", start("game"));

            JsonNode stillThere = tools.call("bot_inspect",
                    arguments("session", "lobby", "name", "SessionA"));
            assertNotNull(stillThere.get("messages"),
                    "the lobby bot was gone after a second session started");

            JsonNode open = tools.call("session_reset", arguments("session", "game", "close", "true"))
                    .get("sessions");
            assertEquals(1, open.size(), "closing 'game' should leave 'lobby': " + open);
            assertEquals("lobby", open.get(0).get("session").asText());
        } finally {
            tools.close();
        }
    }

    /**
     * An unnamed call is ambiguous once there are two servers, and says so rather than guessing.
     */
    @Test
    void anUnnamedCallIsRefusedWhileTwoSessionsAreOpen() throws Exception {
        SessionTools tools = new SessionTools();
        try {
            tools.call("session_start", start("lobby"));
            tools.call("session_start", start("game"));

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> tools.call("bot_spawn", arguments("name", "SessionC")));
            assertTrue(refused.getMessage().contains("lobby")
                            && refused.getMessage().contains("game"),
                    "the refusal should name the open sessions: " + refused.getMessage());
        } finally {
            tools.close();
        }
    }

    private static ObjectNode start(String name) {
        return arguments(
                "session", name,
                "host", HOST,
                "port", String.valueOf(PORT),
                "mcpPort", String.valueOf(MCP_PORT),
                "token", TOKEN,
                "runnerJar", RUNNER_JAR.toString());
    }

    /** Ports arrive as text and are read with asInt, which parses either. */
    private static ObjectNode arguments(String... pairs) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        for (int i = 0; i < pairs.length; i += 2) {
            node.put(pairs[i], pairs[i + 1]);
        }
        return node;
    }
}
