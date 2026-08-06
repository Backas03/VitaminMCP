package moe.vitamin.minecraft.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
