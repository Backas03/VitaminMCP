package moe.vitamin.minecraft.mcp.agent.core;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
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

/** Waits for a condition to become true, checking once per server tick. */
public final class WaitForService {

    /** Ticks per second, which is what one check per tick amounts to. */
    private static final long TICK_PERIOD = 1L;

    /** Events and log lines attached to a timeout. */
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

    /** Blocks until {@code condition} holds, or {@code timeout} elapses. */
    public WaitResult await(WaitCondition condition, Duration timeout) {
        long startedAt = System.nanoTime();

        long eventsFrom = condition.integer("sinceSequence", -1) >= 0
                ? condition.integer("sinceSequence", 0)
                : events.written();

        long logsFrom = logs.written();

        java.util.regex.Pattern logPattern = WaitCondition.LOG_MATCHES.equals(condition.type())
                ? java.util.regex.Pattern.compile(required(condition, "pattern"))
                : null;
        moe.vitamin.minecraft.mcp.contract.LogLevel logLevel =
                WaitCondition.LOG_MATCHES.equals(condition.type())
                        && condition.string("level", null) != null
                        ? moe.vitamin.minecraft.mcp.contract.LogLevel.valueOf(
                                condition.string("level", "").toUpperCase(Locale.ROOT))
                        : null;

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
                            : holds(condition, eventsFrom, logsFrom, logPattern, logLevel);
                    if (done) {
                        outcome.complete(true);
                    }
                } catch (RuntimeException e) {

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

        return new WaitResult(
                false,
                condition.describe(),
                elapsedMillis,
                ticks.get(),
                events.read(Math.max(0, events.written() - SNAPSHOT_SIZE), SNAPSHOT_SIZE,
                        record -> highFrequency.allowedInQuery(record.type(), null)).items(),
                logs.read(Math.max(0, logs.written() - SNAPSHOT_SIZE), SNAPSHOT_SIZE, null).items());
    }

    /** Evaluates one condition. */
    private boolean holds(
            WaitCondition condition,
            long eventsFrom,
            long logsFrom,
            java.util.regex.Pattern logPattern,
            moe.vitamin.minecraft.mcp.contract.LogLevel logLevel) {
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

            case WaitCondition.INVENTORY_OPEN -> {
                Player player = Bukkit.getPlayerExact(required(condition, "name"));
                if (player == null) {
                    yield false;
                }
                org.bukkit.inventory.InventoryView view = player.getOpenInventory();

                if (!moe.vitamin.minecraft.mcp.contract.InventorySnapshot
                        .isMenu(view.getType().name())) {
                    yield false;
                }
                String wanted = condition.string("title", null);
                yield wanted == null || plainTitle(view).contains(wanted);
            }

            case WaitCondition.INVENTORY_CONTAINS -> {
                Player player = Bukkit.getPlayerExact(required(condition, "name"));
                if (player == null) {
                    yield false;
                }
                org.bukkit.inventory.Inventory inventory =
                        "player".equalsIgnoreCase(condition.string("which", "menu"))
                                ? player.getInventory()
                                : player.getOpenInventory().getTopInventory();

                String material = required(condition, "material").toUpperCase(Locale.ROOT);

                int slot = condition.integer("slot", -1);
                if (slot >= 0) {
                    yield slot < inventory.getSize() && matches(inventory.getItem(slot), material);
                }
                for (int index = 0; index < inventory.getSize(); index++) {
                    if (matches(inventory.getItem(index), material)) {
                        yield true;
                    }
                }
                yield false;
            }

            case WaitCondition.LOG_MATCHES -> !logs.read(logsFrom, 1, entry ->
                    (logLevel == null || entry.level().atLeast(logLevel))
                            && logPattern.matcher(entry.message()).find())
                    .items().isEmpty();

            default -> throw new IllegalArgumentException(
                    "Unknown wait condition '" + condition.type() + "'. Known types: "
                            + List.of(WaitCondition.TICKS, WaitCondition.BLOCK_IS,
                            WaitCondition.BLOCK_IS_NOT, WaitCondition.EVENT,
                            WaitCondition.PLAYER_ONLINE, WaitCondition.PLAYER_OFFLINE,
                            WaitCondition.PLAYER_NEAR, WaitCondition.PLAYER_STATE,
                            WaitCondition.INVENTORY_OPEN, WaitCondition.INVENTORY_CONTAINS,
                            WaitCondition.LOG_MATCHES));
        };
    }

    private static boolean matches(org.bukkit.inventory.ItemStack stack, String material) {
        return stack != null && stack.getType().name().equals(material);
    }

    /**
     * A view's title as plain text, so a caller can match on words without knowing how the plugin
     * coloured them.
     */
    private static String plainTitle(org.bukkit.inventory.InventoryView view) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(view.title());
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
