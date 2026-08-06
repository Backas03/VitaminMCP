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

/** Runs scenarios against a real server. */
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

    /** The whole point of the inventory work: something opens a menu, and the menu is checked. */
    @Test
    void aMenuIsOpenedReadAndClicked() throws Exception {

        BotRunner.BotHandle bot = bots.spawn("Tester1");
        int x = bot.blockX() + 1;
        int y = bot.blockY();
        int z = bot.blockZ();

        ScenarioResult result = run("""
                [
                  {"action":"console","command":"setblock %d %d %d chest"},
                  {"action":"console","command":"setblock %d %d %d air"},
                  {"action":"assert_block","x":%d,"y":%d,"z":%d,"material":"CHEST"},
                  {"action":"console","command":"item replace block %d %d %d container.11 with emerald[custom_name={text:'Buy',color:'green'},lore=[{text:'Costs 10'}]] 1"},
                  {"action":"console","command":"item replace block %d %d %d container.15 with barrier[custom_name={text:'Close',color:'red'}] 3"},
                  {"action":"use_block","bot":"Tester1","x":%d,"y":%d,"z":%d},
                  {"action":"wait_for","condition":"inventory_open","name":"Tester1"},
                  {"action":"wait_for","condition":"inventory_contains","name":"Tester1","material":"EMERALD","slot":11},
                  {"action":"assert_inventory","bot":"Tester1","size":27,"slots":[
                      {"slot":11,"material":"EMERALD","name":"Buy","amount":1,"lore":"Costs 10"},
                      {"slot":15,"material":"BARRIER","name":"Close","amount":3},
                      {"slot":13,"empty":true}
                  ]},
                  {"action":"click_slot","bot":"Tester1","slot":15,"click":"shift_left"},
                  {"action":"assert_event","eventType":"InventoryClickEvent","player":"Tester1"},
                  {"action":"close_menu","bot":"Tester1"}
                ]
                """.formatted(
                        x, y, z,

                        x, y + 1, z,
                        x, y, z,
                        x, y, z, x, y, z, x, y, z));

        assertTrue(result.passed(), result.describe());
    }

    /** The wait has to be capable of not matching. */
    @Test
    void waitingForAMenuDoesNotMatchWhenNoneIsOpen() throws Exception {
        bots.spawn("Tester1");

        com.fasterxml.jackson.databind.node.ObjectNode arguments = AgentClient.arguments();
        arguments.put("condition", "inventory_open");
        arguments.put("name", "Tester1");
        arguments.put("timeoutMillis", 1000);

        assertFalse(agent.call("wait_for", arguments).path("matched").asBoolean(),
                "inventory_open matched although the bot has no menu open");
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

        assertEquals(2, result.failure().index());
        assertEquals("assert_player", result.failure().action());
        assertTrue(result.describe().contains("step 2"), result.describe());

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

        assertNotNull(result.failure().evidence());
        assertTrue(result.failure().evidence().contains("events="), result.failure().evidence());
    }

    @Test
    void thereIsNoSleepStep() {
        ScenarioResult result = run("""
                [{"action":"sleep","millis":1000}]
                """);

        assertFalse(result.passed());

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
