package moe.vitamin.minecraft.mcp.bot.core;

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
    void thePoolCapsHowManyBotsCanExist() throws Exception {
        try (BotPool pool = new BotPool(HOST, PORT, 1)) {
            pool.spawn("Tester1", TIMEOUT);

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> pool.spawn("Tester2", TIMEOUT));
            assertTrue(thrown.getMessage().contains("Bot limit reached"));
        }
    }

    @Test
    void oneNameCanOnlyBeConnectedOnce() throws Exception {
        // Two bots under one name share a derived UUID, and the server kicks the first as a
        // duplicate login — which reads as an unrelated bot vanishing for no reason.
        try (BotPool pool = new BotPool(HOST, PORT, 4)) {
            pool.spawn("Tester1", TIMEOUT);

            assertThrows(IllegalStateException.class, () -> pool.spawn("Tester1", TIMEOUT));
        }
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
     */
    @Test
    void aBlockBrokenByABotIsReportedByTheAgent() throws Exception {
        String token = System.getProperty("vitaminmcp.token", "");
        int mcpPort = Integer.getInteger("vitaminmcp.mcpPort", 25585);

        try (BotSession bot = connect("Tester1")) {
            // Spawn puts the player in the air; without waiting for the fall to finish the
            // block "underfoot" is air and breaking it is a no-op the server never reports.
            bot.awaitGrounded(Duration.ofSeconds(15));

            var position = bot.position();
            int x = (int) Math.floor(position.getX());
            int y = (int) Math.floor(position.getY()) - 1;   // the block underfoot
            int z = (int) Math.floor(position.getZ());

            bot.actions().breakBlock(x, y, z);

            String events = AgentProbe.eventsQuery(mcpPort, token, "BlockBreakEvent");
            assertTrue(events.contains("Tester1"),
                    "agent did not report the break by Tester1. Response: " + events);
        }
    }
}
