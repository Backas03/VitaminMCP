package moe.vitamin.minecraft.mcp.testkit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.BooleanSupplier;
import moe.vitamin.minecraft.mcp.bot.core.BotRunner;
import moe.vitamin.minecraft.mcp.bot.spi.ClientView;
import moe.vitamin.minecraft.mcp.orchestrator.ManagedServer;
import moe.vitamin.minecraft.mcp.orchestrator.PaperDownloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Every feature, on one version, against a server this test starts itself.
 *
 * <p>The gate for adding a protocol. A backend that connects proves only that the handshake
 * matches; what actually varies between versions is further in — the position packet, the
 * loading handshake, item components, boss bars, the scoreboard. Each of those is exercised here
 * against a real server, so "1.21.2 works" means the same thing every time it is said
 * (docs/multi-version.md §4).
 *
 * <pre>
 *   ./gradlew :testkit:test --tests '*CompatibilityLiveTest*' \
 *     -Dvitaminmcp.liveServer=true \
 *     -Dvitaminmcp.agentJar=...\VitaminMCP.jar \
 *     -Dvitaminmcp.runnerJar=...\bot-runner.jar \
 *     -Dvitaminmcp.version=1.21.4 -Dvitaminmcp.protocol=769
 * </pre>
 *
 * <p><b>Failures are collected, not thrown at the first one.</b> A run that stops at the first
 * broken feature says nothing about the twelve after it, and on a new protocol the useful
 * question is which parts work — one run should answer it.
 */
@EnabledIfSystemProperty(named = "vitaminmcp.liveServer", matches = "true")
class CompatibilityLiveTest {

    private static final String VERSION = System.getProperty("vitaminmcp.version", "1.21.8");
    private static final int BUILD = Integer.getInteger("vitaminmcp.paperBuild", 0);
    private static final int EXPECTED_PROTOCOL = Integer.getInteger("vitaminmcp.protocol", 0);
    private static final int PORT = Integer.getInteger("vitaminmcp.port", 25810);
    private static final int AGENT_PORT = Integer.getInteger("vitaminmcp.mcpPort", 25811);

    /** Long enough for a first start that also generates a world. */
    private static final Duration STARTUP = Duration.ofMinutes(5);

    private final List<String> failures = new ArrayList<>();

    @Test
    void everyFeatureWorksOnThisVersion() throws Exception {
        Path agentJar = required("vitaminmcp.agentJar");
        Path runnerJar = required("vitaminmcp.runnerJar");
        Path javaHome = Path.of(System.getProperty("java.home"));

        Path work = Files.createTempDirectory("vitaminmcp-compat-" + VERSION + "-");
        String token = token();

        Path paper = new PaperDownloader().fetch(VERSION, BUILD);
        System.out.println("[compat] " + VERSION + " -> " + paper.getFileName());

        try (ManagedServer server = new ManagedServer(work.resolve("server"), paper, PORT, AGENT_PORT)) {
            server.prepare(null, agentJar, token);
            server.start(javaHome, STARTUP);

            AgentClient agent = new AgentClient("127.0.0.1", AGENT_PORT, token);

            try (BotRunner bots = BotRunner.launch(runnerJar, javaHome, "127.0.0.1", PORT)) {
                exercise(bots, agent);
            }
        } finally {
            deleteQuietly(work);
        }

        if (!failures.isEmpty()) {
            fail(failures.size() + " feature(s) failed on " + VERSION + ":\n  - "
                    + String.join("\n  - ", failures));
        }
    }

    private void exercise(BotRunner bots, AgentClient agent) throws Exception {
        // The runner chose its own backend by pinging the server. If that picked the wrong one
        // nothing below is meaningful, so it is checked before anything is attempted.
        if (EXPECTED_PROTOCOL != 0 && bots.protocol() != EXPECTED_PROTOCOL) {
            fail("the runner loaded the backend for protocol " + bots.protocol()
                    + " but " + VERSION + " speaks " + EXPECTED_PROTOCOL);
        }
        System.out.println("[compat] backend protocol " + bots.protocol());

        // Taken before the bot connects, because the join is the first thing to be waited for
        // and a wait that starts afterwards can only ever time out.
        long beforeJoin = eventSequence(agent);

        // Spawning is not one of the collected failures: everything else needs a bot, and a run
        // that reports twelve consequences of one cause is worse than one that reports the cause.
        BotRunner.BotHandle bot = bots.spawn("Tester1");
        System.out.println("[compat] spawned at " + bot.x() + " " + bot.y() + " " + bot.z());
        assertTrue(bot.y() > 0, "the bot spawned at y=" + bot.y() + ", which is not in a world");

        int bx = bot.blockX();
        int by = bot.blockY();
        int bz = bot.blockZ();

        check("join event", () ->
                require(sawEvent(agent, "PlayerJoinEvent", "Tester1", beforeJoin),
                        "the agent never recorded PlayerJoinEvent for Tester1"));

        check("player state", () -> {
            JsonNode state = playerState(agent, "Tester1");
            require(state.path("online").asBoolean(), "the server does not have Tester1 online");
            require("CREATIVE".equals(state.path("gameMode").asText()),
                    "gameMode was " + state.path("gameMode").asText());
        });

        check("op and deop", () -> {
            console(agent, "op Tester1");
            require(await(() -> playerState(agent, "Tester1").path("op").asBoolean()),
                    "op never took effect");
            console(agent, "deop Tester1");
            require(await(() -> !playerState(agent, "Tester1").path("op").asBoolean()),
                    "deop never took effect");
            console(agent, "op Tester1");
            require(await(() -> playerState(agent, "Tester1").path("op").asBoolean()),
                    "op never took effect the second time");
        });

        // The whole point of the loading handshake: without it the server discards every dig
        // silently, so this is the check that catches a backend that connects and does nothing.
        check("break a block", () -> {
            String before = block(agent, bx, by - 1, bz);
            require(!"AIR".equals(before),
                    "the bot is not standing on anything, so there is nothing to break");

            long since = eventSequence(agent);
            bot.breakBlock(bx, by - 1, bz);
            require(sawEvent(agent, "BlockBreakEvent", "Tester1", since),
                    "no BlockBreakEvent arrived. The block under the bot was " + before
                            + ", now " + block(agent, bx, by - 1, bz));
        });

        check("move", () -> {
            // Asked of the server rather than of the bot. The bot's own position only changes
            // when the server sends a correction, so reading it back would be reading our own
            // claim rather than whether the server accepted the move.
            console(agent, "tp Tester1 " + (bx + 3) + " " + by + " " + bz);
            require(await(() -> Math.abs(playerState(agent, "Tester1").path("x").asDouble()
                    - (bx + 3)) < 2.0), "the server never moved the player");

            bot.moveTo(bx + 4.5, by, bz + 0.5);
            require(await(() -> Math.abs(playerState(agent, "Tester1").path("x").asDouble()
                    - (bx + 4.5)) < 1.5),
                    "the server did not accept the bot's own movement; it has the player at "
                            + playerState(agent, "Tester1").path("x").asDouble());
        });

        // Asserted on the server rather than in the bot's inbox. What comes back from /say is a
        // translatable component, and a client with no language file renders it as its key —
        // "chat.type.text" — so looking for the words there would fail for a reason that has
        // nothing to do with whether chat worked.
        check("chat", () -> {
            long since = eventSequence(agent);
            bot.chat("compat-chat-" + System.nanoTime());
            require(sawEvent(agent, "PlayerCommandPreprocessEvent", "Tester1", since),
                    "the server never saw the bot say anything. Messages: " + messages(bot));
        });

        // The round trip the other way: a command the bot issues, whose reply is literal text
        // and therefore survives to be read. This is the check that catches a server refusing
        // bot commands outright — which it does, silently, when enforce-secure-profile is on.
        check("command", () -> {
            String marker = "compat-cmd-" + System.nanoTime();
            bot.command("/tellraw @s {\"text\":\"" + marker + "\"}");
            require(await(() -> messages(bot).stream().anyMatch(line -> line.contains(marker))),
                    "the bot's own command produced nothing it could see. Messages: "
                            + messages(bot));
        });

        // Where the bot now stands, after the move above.
        int cx = bx + 4;
        int cz = bz;

        check("open a container", () -> {
            console(agent, "setblock " + (cx + 1) + " " + by + " " + cz + " minecraft:chest");
            console(agent, "item replace block " + (cx + 1) + " " + by + " " + cz
                    + " container.0 with minecraft:diamond 3");

            bot.useBlock(cx + 1, by, cz, "UP");
            require(await(() -> menuOpen(bot)),
                    "the chest never opened on the client. The server says the block is "
                            + block(agent, cx + 1, by, cz));
        });

        check("read the open menu", () -> {
            require(menuOpen(bot), "no menu is open, so there is nothing to read");
            ClientView view = bot.inspect();
            require(view.menu() != null, "inspect reported no menu while menu() did");
            require(!view.items().isEmpty(),
                    "the client received no items for the open chest, which had a diamond in it");
            require(view.items().stream().anyMatch(item -> item.amount() == 3),
                    "no slot carried the 3 diamonds that were put in: " + view.items());
        });

        check("click and close", () -> {
            bot.clickSlot(0, "left");
            bot.closeMenu();
            require(await(() -> !menuOpen(bot)), "the menu never closed");
        });

        check("boss bar", () -> {
            console(agent, "bossbar add compat {\"text\":\"Compat Bar\"}");
            console(agent, "bossbar set compat players Tester1");
            console(agent, "bossbar set compat visible true");
            require(await(() -> view(bot).bossBars().stream()
                            .anyMatch(bar -> bar.title().contains("Compat Bar"))),
                    "the boss bar never reached the client: " + view(bot).bossBars());
        });

        check("sidebar scoreboard", () -> {
            console(agent, "scoreboard objectives add compat dummy {\"text\":\"Compat Board\"}");
            console(agent, "scoreboard objectives setdisplay sidebar compat");
            console(agent, "scoreboard players set Tester1 compat 5");
            require(await(() -> view(bot).scoreboard() != null
                            && !view(bot).scoreboard().lines().isEmpty()),
                    "the sidebar never reached the client: " + view(bot).scoreboard());
            require(view(bot).scoreboard().title().contains("Compat Board"),
                    "the sidebar title was '" + view(bot).scoreboard().title() + "'");
        });

        check("right-click an entity", () -> {
            console(agent, "summon minecraft:armor_stand " + (cx + 1) + " " + by + " " + (cz + 1));
            require(await(() -> {
                try {
                    return !bot.useEntity(cx + 1, by, cz + 1, 3.0, null).isBlank();
                } catch (java.io.IOException notYet) {
                    // The spawn packet has not reached the client yet. That is the thing being
                    // waited for, so it is not a failure until the wait runs out.
                    return false;
                }
            }), "the bot was never told about the armour stand next to it");
        });

        check("three bots at once", () -> {
            bots.spawn("Tester2");
            bots.spawn("Tester3");
            for (String name : List.of("Tester1", "Tester2", "Tester3")) {
                require(playerState(agent, name).path("online").asBoolean(),
                        name + " is not online");
            }
        });

        check("despawn", () -> {
            long since = eventSequence(agent);
            bots.despawn("Tester3");
            require(sawEvent(agent, "PlayerQuitEvent", "Tester3", since),
                    "no PlayerQuitEvent after despawning Tester3");
        });

        // A version can pass every check above and still be quietly broken: an API whose shape
        // changed produces a linkage error at the call site, which the compiler cannot see and
        // no assertion here would otherwise notice (docs/design.md §5.5).
        check("no linkage errors on the server", () -> {
            ObjectNode arguments = AgentClient.arguments();
            arguments.put("limit", 50);
            String recent = agent.call("exceptions_recent", arguments).toString();
            for (String linkage : List.of("IncompatibleClassChangeError", "NoSuchMethodError",
                    "NoSuchFieldError", "NoClassDefFoundError", "AbstractMethodError")) {
                require(!recent.contains(linkage),
                        "the server logged a " + linkage + ": " + recent);
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private interface Step {
        void run() throws Exception;
    }

    /** Runs one feature check, recording rather than throwing so the rest still run. */
    private void check(String name, Step step) {
        try {
            step.run();
            System.out.println("[compat] ok    " + name);
        } catch (Exception | AssertionError e) {
            System.out.println("[compat] FAIL  " + name + ": " + e.getMessage());
            failures.add(name + ": " + e.getMessage());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * Polls until something the server was asked to do has been observed.
     *
     * <p>The no-sleep rule is about waiting a fixed time and hoping; this waits for the thing
     * itself and gives up loudly. Where the observable lives on the agent, {@code wait_for} is
     * used instead — this is for the ones that live in the bot's own client state, which the
     * agent cannot see.
     */
    private static boolean await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
            } catch (RuntimeException retry) {
                // Same reasoning as above: not yet is not the same as never.
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static boolean menuOpen(BotRunner.BotHandle bot) {
        try {
            return bot.menu() != null;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static List<String> messages(BotRunner.BotHandle bot) {
        return view(bot).messages();
    }

    /**
     * The client's view, or an empty one.
     *
     * <p>Swallowing the transport failure is right here and only here: these calls sit inside
     * "has it arrived yet" polls, where "the runner is momentarily busy" and "the thing never
     * happened" both mean keep waiting. The wait itself is what reports a real failure.
     */
    private static ClientView view(BotRunner.BotHandle bot) {
        try {
            return bot.inspect();
        } catch (java.io.IOException e) {
            return new ClientView(null, List.of(), List.of(), List.of(), null);
        }
    }

    /** Whether the agent recorded {@code eventType} for {@code player} after {@code since}. */
    private static boolean sawEvent(
            AgentClient agent, String eventType, String player, long since) {
        ObjectNode wait = AgentClient.arguments();
        wait.put("condition", "event");
        wait.put("eventType", eventType);
        wait.put("player", player);
        wait.put("sinceSequence", since);
        wait.put("timeoutTicks", 200);
        return agent.call("wait_for", wait).path("matched").asBoolean();
    }

    private static void console(AgentClient agent, String command) {
        ObjectNode arguments = AgentClient.arguments();
        arguments.put("command", command);
        agent.call("command_exec", arguments);
    }

    private static JsonNode playerState(AgentClient agent, String name) {
        ObjectNode arguments = AgentClient.arguments();
        arguments.put("kind", "player");
        arguments.put("target", name);
        return agent.call("state_query", arguments);
    }

    private static String block(AgentClient agent, int x, int y, int z) {
        ObjectNode arguments = AgentClient.arguments();
        arguments.put("kind", "block");
        arguments.put("world", "world");
        arguments.put("x", x);
        arguments.put("y", y);
        arguments.put("z", z);
        return agent.call("state_query", arguments).path("block").asText();
    }

    /**
     * Where the event log is now, so a wait cannot miss what happens next.
     *
     * <p>Taken before acting rather than after: an event landing between the action and the wait
     * would otherwise be missed, and the wait would start after the thing it is waiting for.
     */
    private static long eventSequence(AgentClient agent) {
        String cursor = agent.call("server_info", AgentClient.arguments())
                .path("latestEventCursor").asText("");
        int colon = cursor.indexOf(':');
        return colon < 0 ? 0 : Long.parseLong(cursor.substring(colon + 1));
    }

    private static Path required(String property) {
        Path path = Path.of(System.getProperty(property, ""));
        assertTrue(Files.exists(path), "pass -D" + property + "=<path>");
        return path;
    }

    private static String token() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Best-effort cleanup.
     *
     * <p>Not an assertion: this directory has just hosted a server JVM, and Windows keeps files
     * mapped for a while after one exits. A locked jar must not be able to report a failure that
     * every feature check disagrees with.
     */
    private static void deleteQuietly(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException stillInUse) {
                    // Left behind on purpose. Nothing here depends on it being gone.
                }
            });
        } catch (java.io.IOException e) {
            System.err.println("could not clean " + root + ": " + e.getMessage());
        }
    }
}
