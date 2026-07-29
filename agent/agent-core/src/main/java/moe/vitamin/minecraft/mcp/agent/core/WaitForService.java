package moe.vitamin.minecraft.mcp.agent.core;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import moe.vitamin.minecraft.mcp.contract.Cursor;
import moe.vitamin.minecraft.mcp.contract.EventRecord;
import moe.vitamin.minecraft.mcp.contract.LogEntry;
import moe.vitamin.minecraft.mcp.contract.WaitCondition;
import moe.vitamin.minecraft.mcp.contract.WaitResult;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Waits for a condition to become true, checking once per server tick.
 *
 * <p>This is the piece that lets scenarios stop sleeping. A caller that sleeps has guessed how
 * long something takes, and the guess is calibrated on whatever machine it was written on; the
 * same scenario on a loaded server sleeps too little and fails, and on an idle one wastes the
 * difference. Waiting for the thing itself is right on both.
 *
 * <p>The check runs on the server's main thread because that is the only place world and
 * player state can be read safely, and it runs on a tick because that is the rate at which
 * that state can actually change. Polling faster would burn main-thread time to observe
 * nothing new.
 *
 * <p>The HTTP thread blocks on the result. That is deliberate: the caller asked to wait, and
 * bounding it with a timeout means a wedged condition returns a failure with evidence rather
 * than hanging.
 */
public final class WaitForService {

    /** Ticks per second, which is what one check per tick amounts to. */
    private static final long TICK_PERIOD = 1L;

    /** Events and log lines attached to a timeout. Enough to explain, small enough to read. */
    private static final int SNAPSHOT_SIZE = 40;

    private final Plugin plugin;
    private final SequencedRingBuffer<EventRecord> events;
    private final SequencedRingBuffer<LogEntry> logs;
    private final HighFrequencyEvents highFrequency;

    WaitForService(
            Plugin plugin,
            SequencedRingBuffer<EventRecord> events,
            SequencedRingBuffer<LogEntry> logs,
            HighFrequencyEvents highFrequency) {
        this.plugin = plugin;
        this.events = events;
        this.logs = logs;
        this.highFrequency = highFrequency;
    }

    /**
     * Blocks until {@code condition} holds, or {@code timeout} elapses.
     *
     * <p>Never throws on timeout — a timeout is an answer, and one the caller needs the
     * snapshot from. Only a broken condition (an unknown type, a missing parameter) is an
     * error.
     */
    public WaitResult await(WaitCondition condition, Duration timeout) {
        long startedAt = System.nanoTime();

        // Captured before the wait starts so an `event` condition matches things that happen
        // during it, not whatever was already in the buffer.
        long eventsFrom = condition.integer("sinceSequence", -1) >= 0
                ? condition.integer("sinceSequence", 0)
                : events.written();

        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        AtomicInteger ticks = new AtomicInteger();
        int targetTicks = WaitCondition.TICKS.equals(condition.type())
                ? condition.integer("count", 1)
                : -1;

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                int elapsed = ticks.incrementAndGet();
                try {
                    boolean done = targetTicks >= 0
                            ? elapsed >= targetTicks
                            : holds(condition, eventsFrom);
                    if (done) {
                        outcome.complete(true);
                    }
                } catch (RuntimeException e) {
                    // A condition that cannot be evaluated is the caller's mistake, not a
                    // reason to keep spinning until the timeout.
                    outcome.completeExceptionally(e);
                }
            }
        }, TICK_PERIOD, TICK_PERIOD);

        boolean matched;
        try {
            matched = Boolean.TRUE.equals(
                    outcome.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (java.util.concurrent.TimeoutException e) {
            matched = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            matched = false;
        } catch (java.util.concurrent.ExecutionException e) {
            task.cancel();
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalArgumentException(String.valueOf(cause));
        } finally {
            task.cancel();
        }

        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
        if (matched) {
            return WaitResult.matched(condition.describe(), elapsedMillis, ticks.get());
        }

        // Only on failure: the state that explains it, while it is still there.
        return new WaitResult(
                false,
                condition.describe(),
                elapsedMillis,
                ticks.get(),
                events.read(Math.max(0, events.written() - SNAPSHOT_SIZE), SNAPSHOT_SIZE,
                        record -> highFrequency.allowedInQuery(record.type(), null)).items(),
                logs.read(Math.max(0, logs.written() - SNAPSHOT_SIZE), SNAPSHOT_SIZE, null).items());
    }

    /** Evaluates one condition. Runs on the main thread. */
    private boolean holds(WaitCondition condition, long eventsFrom) {
        return switch (condition.type()) {
            case WaitCondition.BLOCK_IS -> material(condition).equals(expected(condition));
            case WaitCondition.BLOCK_IS_NOT -> !material(condition).equals(expected(condition));

            case WaitCondition.EVENT -> {
                String type = required(condition, "eventType");
                String player = condition.string("player", null);
                yield !events.read(eventsFrom, 1, record ->
                        record.type().equals(type)
                                && (player == null || player.equalsIgnoreCase(record.player())))
                        .items().isEmpty();
            }

            case WaitCondition.PLAYER_ONLINE ->
                    Bukkit.getPlayerExact(required(condition, "name")) != null;
            case WaitCondition.PLAYER_OFFLINE ->
                    Bukkit.getPlayerExact(required(condition, "name")) == null;

            case WaitCondition.PLAYER_STATE -> {
                Player player = Bukkit.getPlayerExact(required(condition, "name"));
                if (condition.has("online") && !condition.bool("online", true)) {
                    yield player == null;
                }
                if (player == null) {
                    yield false;
                }
                if (condition.has("gameMode")
                        && !player.getGameMode().name().equalsIgnoreCase(
                                condition.string("gameMode", ""))) {
                    yield false;
                }
                if (condition.has("op") && player.isOp() != condition.bool("op", false)) {
                    yield false;
                }
                yield true;
            }

            case WaitCondition.PLAYER_NEAR -> {
                Player player = Bukkit.getPlayerExact(required(condition, "name"));
                if (player == null) {
                    yield false;
                }
                double distance = condition.decimal("distance", 1.0);
                yield player.getLocation().distanceSquared(new org.bukkit.Location(
                        player.getWorld(),
                        condition.decimal("x", 0),
                        condition.decimal("y", 0),
                        condition.decimal("z", 0))) <= distance * distance;
            }

            default -> throw new IllegalArgumentException(
                    "Unknown wait condition '" + condition.type() + "'. Known types: "
                            + List.of(WaitCondition.TICKS, WaitCondition.BLOCK_IS,
                            WaitCondition.BLOCK_IS_NOT, WaitCondition.EVENT,
                            WaitCondition.PLAYER_ONLINE, WaitCondition.PLAYER_OFFLINE,
                            WaitCondition.PLAYER_NEAR, WaitCondition.PLAYER_STATE));
        };
    }

    private String material(WaitCondition condition) {
        String worldName = condition.string("world", null);
        org.bukkit.World world = worldName == null || worldName.isBlank()
                ? Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0)
                : Bukkit.getWorld(worldName);
        if (world == null) {
            throw new IllegalArgumentException("No such world: " + worldName);
        }
        return world.getBlockAt(
                        condition.integer("x", 0),
                        condition.integer("y", 0),
                        condition.integer("z", 0))
                .getType().name();
    }

    private static String expected(WaitCondition condition) {
        return required(condition, "material").toUpperCase(java.util.Locale.ROOT);
    }

    private static String required(WaitCondition condition, String key) {
        String value = condition.string(key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Condition '" + condition.type() + "' needs parameter '" + key + "'");
        }
        return value;
    }

    /** The cursor a caller should pass as {@code sinceSequence} to catch events from now on. */
    public String eventCursor() {
        return new Cursor(Cursor.EVENTS, events.written()).encode();
    }
}
