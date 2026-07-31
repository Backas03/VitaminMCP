package moe.vitamin.minecraft.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The session's own lifecycle, against a real server.
 *
 * <pre>
 *   ./gradlew :mcp-server:test -Dvitaminmcp.liveServer=true -Dvitaminmcp.token=... \
 *       -Dvitaminmcp.runnerJar=/path/to/bot-runner-772.jar
 * </pre>
 */
@EnabledIfSystemProperty(named = "vitaminmcp.liveServer", matches = "true")
class SessionLiveTest {

    private static final String HOST = System.getProperty("vitaminmcp.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("vitaminmcp.port", 25565);
    private static final int MCP_PORT = Integer.getInteger("vitaminmcp.mcpPort", 25585);
    private static final String TOKEN = System.getProperty("vitaminmcp.token", "");
    private static final Path RUNNER_JAR =
            Path.of(System.getProperty("vitaminmcp.runnerJar", ""));

    /**
     * Reset has to leave the session usable, which is the whole difference between it and close.
     *
     * <p>Pinned because it did not. Reset disconnected the bots by destroying the runner process
     * and never started another, so every later spawn failed with "the bot runner has exited" —
     * and reset itself had reported success, which put the blame on whatever ran next. A tool
     * documented as keeping the connection has to keep the thing the connection is for.
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
