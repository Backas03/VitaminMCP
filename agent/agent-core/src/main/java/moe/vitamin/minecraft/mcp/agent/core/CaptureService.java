package moe.vitamin.minecraft.mcp.agent.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import moe.vitamin.minecraft.mcp.contract.CommandResult;
import moe.vitamin.minecraft.mcp.contract.Cursor;
import moe.vitamin.minecraft.mcp.contract.EventRecord;
import moe.vitamin.minecraft.mcp.contract.EventsSummary;
import moe.vitamin.minecraft.mcp.contract.ExceptionGroup;
import moe.vitamin.minecraft.mcp.contract.LogEntry;
import moe.vitamin.minecraft.mcp.contract.LogLevel;
import moe.vitamin.minecraft.mcp.contract.PlayerState;
import moe.vitamin.minecraft.mcp.contract.ServerInfo;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * The agent's whole read surface, in one place.
 *
 * <p>Owns the two ring buffers, the exception registry and the two capture mechanisms, and
 * answers the queries the MCP tools expose. Keeping this separate from the transport is what
 * lets the capture logic be exercised without an HTTP server, and lets agent-mcp stay a thin
 * translation layer.
 *
 * <p>Every query here runs on a request thread, never on the server's main thread.
 */
public final class CaptureService implements AgentQueries {

    private final SequencedRingBuffer<EventRecord> events;
    private final SequencedRingBuffer<LogEntry> logs;
    private final ExceptionRegistry exceptions;
    private final HighFrequencyEvents highFrequency;
    private final EventCapture eventCapture;
    private final LogCapture logCapture;
    private final WaitForService waitForService;
    private final Plugin plugin;
    private final long startedAt = System.currentTimeMillis();

    private boolean logCaptureActive;

    public CaptureService(Plugin plugin, AgentSettings settings) {
        this.plugin = plugin;
        this.highFrequency = settings.highFrequencyEvents();
        this.events = new SequencedRingBuffer<>(settings.eventBufferSize());
        this.logs = new SequencedRingBuffer<>(settings.logBufferSize());
        this.exceptions = new ExceptionRegistry(settings.maxExceptionGroups());
        this.eventCapture = new EventCapture(
                plugin, events, highFrequency, settings.captureHighFrequency(), settings.scanPackages());
        this.logCapture = new LogCapture(logs, exceptions, plugin.getLogger());
        this.waitForService = new WaitForService(plugin, events, logs, highFrequency);
    }

    public void start() {
        eventCapture.start();
        logCaptureActive = logCapture.attach();
    }

    public void stop() {
        eventCapture.stop();
        logCapture.detach();
        logCaptureActive = false;
    }

    // ---------------------------------------------------------------- events

    /**
     * Counts events by type over a window.
     *
     * <p>Reports every type, including the high-frequency ones excluded from detail queries.
     * Seeing "PlayerMoveEvent ×48210" is exactly how a caller learns why its detail query
     * returned nothing interesting, and what it would cost to ask for it anyway.
     *
     * @param from epoch millis, inclusive; {@code 0} for everything retained
     * @param to   epoch millis, exclusive; {@code 0} for "up to now"
     */
    public EventsSummary summarize(long from, long to) {
        long windowEnd = to <= 0 ? Long.MAX_VALUE : to;
        Map<String, long[]> counts = new HashMap<>();
        long[] total = {0};

        long dropped = events.forEachRetained(0, record -> {
            if (record.timestamp() < from || record.timestamp() >= windowEnd) {
                return;
            }
            counts.computeIfAbsent(record.type(), key -> new long[1])[0]++;
            total[0]++;
        });

        List<EventsSummary.TypeCount> typeCounts = counts.entrySet().stream()
                .map(entry -> new EventsSummary.TypeCount(entry.getKey(), entry.getValue()[0]))
                .sorted(EventsSummary.TypeCount.mostFrequentFirst())
                .toList();

        return new EventsSummary(
                from, windowEnd == Long.MAX_VALUE ? System.currentTimeMillis() : windowEnd,
                total[0], dropped, typeCounts);
    }

    /**
     * Reads events matching the given filters.
     *
     * <p>High-frequency types are filtered out unless the caller named them in {@code types}.
     *
     * @param cursorToken opaque resume token, or {@code null} to start from the oldest retained
     * @param types       simple type names to include, or {@code null} for "anything but the
     *                    high-frequency set"
     * @param player      restrict to one player, or {@code null}
     * @param limit       maximum records
     */
    public SequencedRingBuffer.Batch<EventRecord> queryEvents(
            String cursorToken, Collection<String> types, String player, int limit) {

        // A caller with no cursor is asking "what has happened", meaning from the beginning —
        // not "from wherever the buffer happens to start now". Anchoring at 0 is what makes the
        // returned `dropped` count the records the buffer has already overwritten. Starting at
        // oldestRetainedSequence() instead reports 0 lost on a first query, which is precisely
        // the moment the caller most needs to know the window is partial (docs/design.md §8).
        long from = cursorToken == null ? 0 : Cursor.parse(cursorToken, Cursor.EVENTS).sequence();

        return events.read(from, limit, record -> {
            if (!highFrequency.allowedInQuery(record.type(), types)) {
                return false;
            }
            if (types != null && !types.isEmpty() && !types.contains(record.type())) {
                return false;
            }
            return player == null || player.equalsIgnoreCase(record.player());
        });
    }

    // ------------------------------------------------------------------ logs

    /**
     * Reads log entries matching the given filters.
     *
     * <p>There is deliberately no "last N lines" equivalent. A pattern search answers the
     * question that actually gets asked, while a tail spends the whole response budget on
     * whatever happened to be most recent (docs/design.md §10).
     *
     * @param minLevel minimum severity, or {@code null} for all
     * @param pattern  regular expression matched against the message, or {@code null}
     */
    public SequencedRingBuffer.Batch<LogEntry> queryLogs(
            String cursorToken, LogLevel minLevel, Pattern pattern, int limit) {

        // Anchored at 0 when there is no cursor, for the same reason as queryEvents.
        long from = cursorToken == null ? 0 : Cursor.parse(cursorToken, Cursor.LOGS).sequence();

        return logs.read(from, limit, entry -> {
            if (minLevel != null && !entry.level().atLeast(minLevel)) {
                return false;
            }
            return pattern == null || pattern.matcher(entry.message()).find();
        });
    }

    // ------------------------------------------------------------ exceptions

    public List<ExceptionGroup> recentExceptions(int limit) {
        return exceptions.recent(limit);
    }

    public ExceptionGroup exceptionByHash(String hash) {
        return exceptions.byHash(hash);
    }

    // ----------------------------------------------------------- server info

    public ServerInfo serverInfo() {
        List<ServerInfo.PluginInfo> plugins = new ArrayList<>();
        for (Plugin installed : plugin.getServer().getPluginManager().getPlugins()) {
            plugins.add(new ServerInfo.PluginInfo(
                    installed.getName(),
                    installed.getPluginMeta().getVersion(),
                    installed.isEnabled()));
        }

        return new ServerInfo(
                Bukkit.getName(),
                Bukkit.getVersion(),
                Bukkit.getBukkitVersion(),
                readTps(),
                Bukkit.getOnlinePlayers().size(),
                Bukkit.getMaxPlayers(),
                System.currentTimeMillis() - startedAt,
                plugins);
    }

    /**
     * Reads the server's TPS averages, if it has any.
     *
     * <p>Reflective because {@code getTPS()} is a Paper addition and the agent compiles against
     * the Bukkit API at 1.13 (see agent-core/build.gradle.kts). Returning an empty list on a
     * server without it is the right outcome — TPS is useful context, not a reason to fail.
     */
    private static List<Double> readTps() {
        try {
            Object raw = Bukkit.getServer().getClass().getMethod("getTPS").invoke(Bukkit.getServer());
            if (raw instanceof double[] values) {
                List<Double> tps = new ArrayList<>(values.length);
                for (double value : values) {
                    tps.add(value);
                }
                return tps;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Not a Paper-family server, or the method moved. Neither is an error.
        }
        return List.of();
    }

    // ---------------------------------------------------------------- status

    /** Diagnostics about capture itself, surfaced through {@code server_info}. */
    public Map<String, Object> captureStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("eventTypesRegistered", eventCapture.registeredTypes().size());
        status.put("eventsCaptured", events.written());
        status.put("eventsDropped", events.overwritten());
        status.put("eventBufferCapacity", events.capacity());
        status.put("logsCaptured", logs.written());
        status.put("logsDropped", logs.overwritten());
        status.put("logBufferCapacity", logs.capacity());
        status.put("logCaptureActive", logCaptureActive);
        status.put("distinctExceptions", exceptions.size());
        status.put("highFrequencyExcluded", List.copyOf(highFrequency.excluded()));
        return status;
    }

    @Override
    public moe.vitamin.minecraft.mcp.contract.WaitResult waitFor(
            moe.vitamin.minecraft.mcp.contract.WaitCondition condition, Duration timeout) {
        return waitForService.await(condition, timeout);
    }

    // ------------------------------------------------------------ world state

    @Override
    public PlayerState playerState(String name, Collection<String> permissionNodes) {
        return onMainThread(() -> {
            org.bukkit.entity.Player player = Bukkit.getPlayerExact(name);
            if (player == null) {
                org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
                return new PlayerState(
                        name, String.valueOf(offline.getUniqueId()), false,
                        null, null, offline.isOp(), null, 0, 0, 0, List.of());
            }

            List<PlayerState.PermissionCheck> checks = new ArrayList<>();
            if (permissionNodes != null) {
                for (String node : permissionNodes) {
                    checks.add(new PlayerState.PermissionCheck(node, player.hasPermission(node)));
                }
            }

            org.bukkit.Location at = player.getLocation();
            return new PlayerState(
                    player.getName(),
                    player.getUniqueId().toString(),
                    true,
                    hostAddress(player.getAddress()),
                    player.getGameMode().name(),
                    player.isOp(),
                    at.getWorld() == null ? null : at.getWorld().getName(),
                    at.getX(), at.getY(), at.getZ(),
                    checks);
        }, Duration.ofSeconds(5), null);
    }

    /**
     * The IP out of a socket address, without the port.
     *
     * <p>The port is the client's ephemeral one and changes every connection, so including it
     * would make the field useless for the comparison anyone actually wants to make — against a
     * ban list, a whitelist, or the address a proxy claimed to be forwarding.
     *
     * @return the address, or {@code null} if the player is already on the way out
     */
    private static String hostAddress(java.net.InetSocketAddress address) {
        if (address == null) {
            // Bukkit returns null once the connection is closing, which a query can race.
            return null;
        }
        java.net.InetAddress ip = address.getAddress();
        return ip == null ? address.getHostString() : ip.getHostAddress();
    }

    @Override
    public String blockAt(String world, int x, int y, int z) {
        return onMainThread(() -> {
            org.bukkit.World target = world == null || world.isBlank()
                    ? Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0)
                    : Bukkit.getWorld(world);
            return target == null ? null : target.getBlockAt(x, y, z).getType().name();
        }, Duration.ofSeconds(5), null);
    }

    @Override
    public CommandResult executeCommand(String command, String asPlayer, Duration timeout) {
        String normalised = command.startsWith("/") ? command.substring(1) : command;
        String executedAs = asPlayer == null ? CommandResult.CONSOLE : asPlayer;

        // The log stream is the only place most commands report anything: Bukkit tells us
        // whether a handler ran, not what it decided. Bracketing the call with cursors turns
        // the capture we already have into the command's output.
        long from = logs.written();
        long startedAt = System.nanoTime();

        Boolean dispatched = onMainThread(() -> {
            org.bukkit.command.CommandSender sender = asPlayer == null
                    ? Bukkit.getConsoleSender()
                    : Bukkit.getPlayerExact(asPlayer);
            if (sender == null) {
                throw new IllegalArgumentException("No player online named " + asPlayer);
            }
            return Bukkit.dispatchCommand(sender, normalised);
        }, timeout, Boolean.FALSE);

        long millis = (System.nanoTime() - startedAt) / 1_000_000;

        List<String> output = new ArrayList<>();
        logs.read(from, 200, null).items()
                .forEach(entry -> output.add(entry.message()));

        return new CommandResult(
                normalised, executedAs, Boolean.TRUE.equals(dispatched), output, millis);
    }

    /**
     * Runs something on the server's main thread and waits for it.
     *
     * <p>Anything touching worlds, players or commands has to run there — Bukkit is not thread
     * safe and doing it from an HTTP thread corrupts state in ways that surface much later.
     * The wait is bounded so a wedged main thread returns an error to the caller rather than
     * tying up the request thread indefinitely.
     */
    private <T> T onMainThread(java.util.concurrent.Callable<T> work, Duration timeout, T fallback) {
        try {
            return Bukkit.getScheduler()
                    .callSyncMethod(plugin, work)
                    .get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException(
                    "The server's main thread did not respond within " + timeout, e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause == null ? e : cause);
        }
    }

    /** Cursor pointing just past the newest event, for callers that only want what happens next. */
    public String latestEventCursor() {
        return new Cursor(Cursor.EVENTS, events.written()).encode();
    }

    /** Cursor pointing just past the newest log entry. */
    public String latestLogCursor() {
        return new Cursor(Cursor.LOGS, logs.written()).encode();
    }
}
