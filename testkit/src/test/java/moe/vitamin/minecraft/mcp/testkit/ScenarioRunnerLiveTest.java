package moe.vitamin.minecraft.mcp.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import moe.vitamin.minecraft.mcp.bot.core.BotRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Runs scenarios against a real server.
 *
 * <pre>
 *   ./gradlew :testkit:test -Dvitaminmcp.liveServer=true -Dvitaminmcp.token=...
 * </pre>
 *
 * <p>Needs the agent running with {@code read-only: false}, since scenarios use the console.
 */
@EnabledIfSystemProperty(named = "vitaminmcp.liveServer", matches = "true")
class ScenarioRunnerLiveTest {

    private static final String HOST = System.getProperty("vitaminmcp.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("vitaminmcp.port", 25565);

    private final AgentClient agent = new AgentClient(
            HOST,
            Integer.getInteger("vitaminmcp.mcpPort", 25585),
            System.getProperty("vitaminmcp.token", ""));

    private BotRunner bots;

    @org.junit.jupiter.api.BeforeEach
    void launchRunner() throws Exception {
        // The runner built for this server's protocol. Bots live in a child process, so this
        // JVM never links MCProtocolLib at all.
        bots = BotRunner.launch(
                Path.of(System.getProperty("vitaminmcp.runnerJar", "")),
                Path.of(System.getProperty("java.home")), HOST, PORT);
    }

    @AfterEach
    void disconnectBots() {
        if (bots != null) {
            bots.close();
        }
    }

    private ScenarioResult run(String scenario) {
        return new ScenarioRunner(bots, agent).run(scenario);
    }

    @Test
    void aBotBreaksABlockAndTheAgentConfirmsIt() throws Exception {
        // Coordinates are not known until the bot has landed, so the scenario is built around
        // where it actually is. A scenario with hard-coded coordinates would be testing the
        // world rather than the plugin.
        BotRunner.BotHandle scout = bots.spawn("Tester1");
        int x = scout.blockX();
        int y = scout.blockY() - 1;
        int z = scout.blockZ();
        bots.despawn("Tester1");

        ScenarioResult result = run("""
                [
                  {"action":"spawn","bot":"Tester1"},
                  {"action":"assert_player","bot":"Tester1","online":true,"gameMode":"CREATIVE"},
                  {"action":"assert_block","x":%d,"y":%d,"z":%d,"material":"%s"},
                  {"action":"break_block","bot":"Tester1","x":%d,"y":%d,"z":%d},
                  {"action":"wait_for","condition":"block_is","x":%d,"y":%d,"z":%d,"material":"AIR"},
                  {"action":"assert_event","eventType":"BlockBreakEvent","player":"Tester1"}
                ]
                """.formatted(x, y, z, agentBlockAt(x, y, z), x, y, z, x, y, z));

        assertTrue(result.passed(), result.describe());
        assertEquals(6, result.steps().size());
    }

    @Test
    void theConsoleCanGrantOpAndTheAgentSeesIt() {
        // Establishes its own precondition rather than assuming one. op survives in ops.json
        // across restarts, so a scenario that assumes a fresh player fails on the second run
        // for reasons that have nothing to do with what it tests — the accumulating,
        // untraceable failure docs/design.md §13 warns about. Proper isolation is a world reset
        // per run, which is Stage 5; until then a scenario sets up what it depends on.
        ScenarioResult result = run("""
                [
                  {"action":"console","command":"deop Tester1"},
                  {"action":"spawn","bot":"Tester1"},
                  {"action":"assert_player","bot":"Tester1","op":false},
                  {"action":"console","command":"op Tester1"},
                  {"action":"assert_player","bot":"Tester1","op":true},
                  {"action":"console","command":"deop Tester1"},
                  {"action":"assert_player","bot":"Tester1","op":false}
                ]
                """);

        assertTrue(result.passed(), result.describe());
    }

    @Test
    void aFailingStepIsNamedExactly() {
        ScenarioResult result = run("""
                [
                  {"action":"spawn","bot":"Tester1"},
                  {"action":"assert_player","bot":"Tester1","gameMode":"SURVIVAL"},
                  {"action":"console","command":"say this step must never run"}
                ]
                """);

        assertFalse(result.passed());
        // The DoD is that a failure points at the step, not that it merely reports failure.
        assertEquals(2, result.failure().index());
        assertEquals("assert_player", result.failure().action());
        assertTrue(result.describe().contains("step 2"), result.describe());
        // And it stops: a step after a failure runs against a state nobody described.
        assertEquals(2, result.steps().size());
    }

    @Test
    void aTimeoutBringsBackTheStateThatExplainsIt() {
        ScenarioResult result = run("""
                [
                  {"action":"wait_for","condition":"player_online","name":"NeverConnects",
                   "timeoutMillis":2000}
                ]
                """);

        assertFalse(result.passed());
        assertTrue(result.failure().detail().contains("timed out"), result.failure().detail());
        // Without this a timeout sends the reader back to a server that has already moved on.
        assertNotNull(result.failure().evidence());
        assertTrue(result.failure().evidence().contains("events="), result.failure().evidence());
    }

    @Test
    void thereIsNoSleepStep() {
        ScenarioResult result = run("""
                [{"action":"sleep","millis":1000}]
                """);

        assertFalse(result.passed());
        // Refused with the reason, so whoever reached for it learns what to use instead.
        assertTrue(result.failure().detail().contains("wait_for"), result.failure().detail());
    }

    @Test
    void anUnknownActionFailsAtThatStep() {
        ScenarioResult result = run("""
                [{"action":"spawn","bot":"Tester1"},{"action":"teleport_to_the_moon"}]
                """);

        assertFalse(result.passed());
        assertEquals(2, result.failure().index());
        assertTrue(result.failure().detail().contains("unknown action"), result.failure().detail());
    }

    private String agentBlockAt(int x, int y, int z) {
        var arguments = AgentClient.arguments();
        arguments.put("kind", "block");
        arguments.put("world", "world");
        arguments.put("x", x);
        arguments.put("y", y);
        arguments.put("z", z);
        return agent.call("state_query", arguments).path("block").asText();
    }
}
