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

/**
 * Connects real bots to a real server.
 *
 * <p>Gated behind a system property so an ordinary build never depends on a server being up:
 *
 * <pre>
 *   ./gradlew :bot-core:test -Dvitaminmcp.liveServer=true
 * </pre>
 *
 * <p>The backend must be {@code online-mode=false} with {@code settings.bungeecord: true} in
 * spigot.yml, which is what makes it trust the forwarded identity (docs/design.md §3.1).
 */
@EnabledIfSystemProperty(named = "vitaminmcp.liveServer", matches = "true")
class BotConnectionLiveTest {

    private static final String HOST =
            System.getProperty("vitaminmcp.host", "127.0.0.1");
    private static final int PORT =
            Integer.getInteger("vitaminmcp.port", 25565);

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static BotSession connect(String name) throws Exception {
        return BotSession.open(HOST, PORT, BotIdentity.of(name), "localhost", "127.0.0.1")
                .connect(TIMEOUT);
    }

    @Test
    void aBotReachesTheWorldThroughTheForwardingHandshake() throws Exception {
        try (BotSession bot = connect("Tester1")) {
            // isInGame means the join packet arrived, not merely that a socket opened — the
            // difference between "logged in" and "the server rejected us a moment later".
            assertTrue(bot.isInGame(), "bot did not reach the world: " + bot.disconnectReason());
        }
    }

    @Test
    void theInjectedUuidIsWhatTheBotClaims() throws Exception {
        BotIdentity identity = BotIdentity.of("Tester1");

        // Derived, not random: the same name must land on the same UUID every run, which is
        // what makes permission-dependent tests reproducible.
        assertEquals(BotIdentity.offlineUuid("Tester1"), identity.uuid());
        assertNotEquals(BotIdentity.offlineUuid("Tester2"), identity.uuid());
    }



    @Test
    void aBotKnowsWhereItStands() throws Exception {
        try (BotSession bot = connect("Tester1")) {
            // connect() waits for the position, not just the join, so this is never null by
            // the time a caller can act on it.
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

            // Held open together, not connected and dropped in turn: the DoD is concurrent
            // presence, and a server that admits them one at a time can still fail this.
            for (BotSession bot : bots) {
                assertTrue(bot.isInGame(),
                        bot.identity().name() + " dropped: " + bot.disconnectReason());
            }
        } finally {
            bots.forEach(BotSession::close);
        }
    }

    /**
     * The point where the two halves of the project meet: a bot acts, and the agent installed
     * on the same server reports it. Until this passes they are two unrelated programs.
     *
     * <p>Written to diagnose itself. Every earlier attempt failed with nothing to go on but an
     * empty event list, because the test could only see what the bot believed. Asking the
     * agent what the server actually holds — the game mode, the block that is really there —
     * turns a silent failure into a specific one.
     */
    @Test
    void aBlockBrokenByABotIsReportedByTheAgent() throws Exception {
        AgentProbe agent = AgentProbe.fromSystemProperties();

        try (BotSession bot = connect("Tester1")) {
            // Spawn puts the player in the air; until the fall finishes the block "underfoot"
            // is air, and breaking air is a no-op the server never reports.
            bot.awaitGrounded(Duration.ofSeconds(15));

            var position = bot.position();
            int x = (int) Math.floor(position.getX());
            int y = (int) Math.floor(position.getY()) - 1;
            int z = (int) Math.floor(position.getZ());

            String gameMode = agent.gameModeOf("Tester1");
            String before = agent.blockAt("world", x, y, z);

            // Taken before acting, so an event landing between the break and the wait still
            // counts. Without it the wait could start after the thing it is waiting for.
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

    /**
     * The same scenario, many times over.
     *
     * <p>Flakiness is invisible in a single run by definition — the run that passes tells you
     * nothing about the one that will not. Repetition is the only way to see it, so this is the
     * test that decides whether the timing work actually worked (docs/roadmap.md Stage 3 DoD).
     *
     * <p>Iterations default low so an ordinary live run stays quick; the DoD figure is passed
     * in deliberately:
     *
     * <pre>
     *   -Dvitaminmcp.repeat=50
     * </pre>
     */
    @Test
    void theSameScenarioSucceedsEveryTime() throws Exception {
        int iterations = Integer.getInteger("vitaminmcp.repeat", 5);
        AgentProbe agent = AgentProbe.fromSystemProperties();
        List<String> failures = new ArrayList<>();

        for (int run = 1; run <= iterations; run++) {
            try (BotSession bot = connect("Tester1")) {
                bot.awaitGrounded(Duration.ofSeconds(15));

                var at = bot.position();
                int x = (int) Math.floor(at.getX());
                int y = (int) Math.floor(at.getY()) - 1;
                int z = (int) Math.floor(at.getZ());

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
