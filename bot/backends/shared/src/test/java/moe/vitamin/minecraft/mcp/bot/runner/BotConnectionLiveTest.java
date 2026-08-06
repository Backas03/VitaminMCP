package moe.vitamin.minecraft.mcp.bot.runner;

import moe.vitamin.minecraft.mcp.bot.core.BotIdentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Connects real bots to a real server. */
@EnabledIfSystemProperty(named = "vitaminmcp.liveServer", matches = "true")
class BotConnectionLiveTest {

    private static final String HOST =
            System.getProperty("vitaminmcp.host", "127.0.0.1");
    private static final int PORT =
            Integer.getInteger("vitaminmcp.port", 25565);

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static BotSession connect(String name) throws Exception {
        return BotSession.open(HOST, PORT, BotIdentity.of(name)).connect(TIMEOUT);
    }

    private static BotSession connect(String name, String clientIp) throws Exception {
        return BotSession.open(HOST, PORT, BotIdentity.of(name), null, clientIp).connect(TIMEOUT);
    }

    @Test
    void aBotReachesTheWorldThroughTheForwardingHandshake() throws Exception {
        try (BotSession bot = connect("Tester1")) {

            assertTrue(bot.isInGame(), "bot did not reach the world: " + bot.disconnectReason());
        }
    }

    @Test
    void theInjectedUuidIsWhatTheBotClaims() throws Exception {
        BotIdentity identity = BotIdentity.of("Tester1");

        assertEquals(BotIdentity.offlineUuid("Tester1"), identity.uuid());
        assertNotEquals(BotIdentity.offlineUuid("Tester2"), identity.uuid());
    }

    /**
     * The address half of the forwarded identity, checked the only way it can be: by asking the
     * server what it recorded.
     */
    @Test
    void aBotCanClaimTheAddressItConnectsFrom() throws Exception {
        AgentProbe agent = AgentProbe.fromSystemProperties();

        try (BotSession bot = connect("Tester1", "203.0.113.7")) {
            assertTrue(bot.isInGame(), "bot did not reach the world: " + bot.disconnectReason());
            assertEquals("203.0.113.7", agent.addressOf("Tester1"),
                    "the server did not attribute the connection to the claimed address");
        }
    }

    /** Without a claim, the bot reports where it really is. */
    @Test
    void withoutAClaimTheServerSeesTheRealAddress() throws Exception {
        AgentProbe agent = AgentProbe.fromSystemProperties();

        String expected;
        try (java.net.Socket probe = new java.net.Socket(HOST, PORT)) {
            expected = probe.getLocalAddress().getHostAddress();
        }

        try (BotSession bot = connect("Tester1")) {
            assertTrue(bot.isInGame(), "bot did not reach the world: " + bot.disconnectReason());
            assertEquals(expected, agent.addressOf("Tester1"),
                    "the server recorded an address this machine does not connect from");
        }
    }

    @Test
    void aBotKnowsWhereItStands() throws Exception {
        try (BotSession bot = connect("Tester1")) {

            assertNotNull(bot.position(), "no position was received");
        }
    }

    @Test
    void threeBotsStayConnectedAtOnce() throws Exception {
        List<BotSession> bots = new ArrayList<>();
        try {
            for (String name : List.of("Tester1", "Tester2", "Tester3")) {
                bots.add(connect(name));
            }

            for (BotSession bot : bots) {
                assertTrue(bot.isInGame(),
                        bot.identity().name() + " dropped: " + bot.disconnectReason());
            }
        } finally {
            bots.forEach(BotSession::close);
        }
    }

    /**
     * The point where the two halves of the project meet: a bot acts, and the agent installed on
     * the same server reports it.
     */
    @Test
    void aBlockBrokenByABotIsReportedByTheAgent() throws Exception {
        AgentProbe agent = AgentProbe.fromSystemProperties();

        try (BotSession bot = connect("Tester1")) {

            bot.awaitGrounded(Duration.ofSeconds(15));

            var position = bot.position();
            int x = (int) Math.floor(position.x());
            int y = (int) Math.floor(position.y()) - 1;
            int z = (int) Math.floor(position.z());

            String gameMode = agent.gameModeOf("Tester1");
            String before = agent.blockAt("world", x, y, z);

            long since = agent.eventSequence();

            bot.actions().breakBlock(x, y, z);

            String events = agent.awaitEvent(
                    "BlockBreakEvent", "Tester1", since, Duration.ofSeconds(10));
            String after = agent.blockAt("world", x, y, z);

            String diagnosis = "gameMode=" + gameMode
                    + " block(" + x + "," + y + "," + z + ") before=" + before + " after=" + after;

            assertNotEquals("AIR", before,
                    "the bot was not standing on anything, so there was nothing to break. " + diagnosis);
            assertTrue(events.contains("\\\"matched\\\" : true"),
                    "agent never saw a BlockBreakEvent by Tester1. " + diagnosis
                            + " waitResult=" + events);
        }
    }

    /** The same scenario, many times over. */
    @Test
    void theSameScenarioSucceedsEveryTime() throws Exception {
        int iterations = Integer.getInteger("vitaminmcp.repeat", 5);
        AgentProbe agent = AgentProbe.fromSystemProperties();
        List<String> failures = new ArrayList<>();

        for (int run = 1; run <= iterations; run++) {
            try (BotSession bot = connect("Tester1")) {
                bot.awaitGrounded(Duration.ofSeconds(15));

                var at = bot.position();
                int x = (int) Math.floor(at.x());
                int y = (int) Math.floor(at.y()) - 1;
                int z = (int) Math.floor(at.z());

                long since = agent.eventSequence();
                bot.actions().breakBlock(x, y, z);

                String result = agent.awaitEvent(
                        "BlockBreakEvent", "Tester1", since, Duration.ofSeconds(10));
                if (!result.contains("\\\"matched\\\" : true")) {
                    failures.add("run " + run + " at (" + x + "," + y + "," + z + "): " + result);
                }
            }
        }

        assertTrue(failures.isEmpty(),
                failures.size() + " of " + iterations + " runs failed: "
                        + String.join(" | ", failures));
    }
}
