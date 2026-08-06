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
import moe.vitamin.minecraft.mcp.contract.InventorySnapshot;
import moe.vitamin.minecraft.mcp.contract.LogEntry;
import moe.vitamin.minecraft.mcp.contract.LogLevel;
import moe.vitamin.minecraft.mcp.contract.PlayerState;
import moe.vitamin.minecraft.mcp.contract.ServerInfo;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** The agent's whole read surface, in one place. */
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

    /** Counts events by type over a window. */
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

    /** Reads events matching the given filters. */
    public SequencedRingBuffer.Batch<EventRecord> queryEvents(
            String cursorToken, Collection<String> types, String player, int limit) {

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

    /** Reads log entries matching the given filters. */
    public SequencedRingBuffer.Batch<LogEntry> queryLogs(
            String cursorToken, LogLevel minLevel, Pattern pattern, int limit) {

        long from = cursorToken == null ? 0 : Cursor.parse(cursorToken, Cursor.LOGS).sequence();

        return logs.read(from, limit, entry -> {
            if (minLevel != null && !entry.level().atLeast(minLevel)) {
                return false;
            }
            return pattern == null || pattern.matcher(entry.message()).find();
        });
    }

    public List<ExceptionGroup> recentExceptions(int limit) {
        return exceptions.recent(limit);
    }

    public ExceptionGroup exceptionByHash(String hash) {
        return exceptions.byHash(hash);
    }

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

    /** Reads the server's TPS averages, if it has any. */
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

        }
        return List.of();
    }

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

    /** The IP out of a socket address, without the port. */
    private static String hostAddress(java.net.InetSocketAddress address) {
        if (address == null) {

            return null;
        }
        java.net.InetAddress ip = address.getAddress();
        return ip == null ? address.getHostString() : ip.getHostAddress();
    }

    /** Reads a player's inventory, or the menu they have open. */
    @Override
    public InventorySnapshot inventory(String name, boolean openMenu, int limit) {
        return onMainThread(() -> {
            org.bukkit.entity.Player player = Bukkit.getPlayerExact(name);
            if (player == null) {
                return null;
            }

            org.bukkit.inventory.InventoryView view = player.getOpenInventory();

            org.bukkit.inventory.Inventory inventory =
                    openMenu ? view.getTopInventory() : player.getInventory();

            List<InventorySnapshot.Item> items = new ArrayList<>();
            int occupied = 0;
            boolean truncated = false;

            for (int slot = 0; slot < inventory.getSize(); slot++) {
                org.bukkit.inventory.ItemStack stack = inventory.getItem(slot);
                if (stack == null || stack.getType() == org.bukkit.Material.AIR) {
                    continue;
                }
                occupied++;
                if (items.size() >= limit) {

                    truncated = true;
                    continue;
                }
                items.add(describe(slot, stack));
            }

            return new InventorySnapshot(
                    view.getType().name(),

                    openMenu ? legacy(view.title()) : null,
                    inventory.getSize(),
                    occupied,
                    items,
                    truncated);
        }, Duration.ofSeconds(5), null);
    }

    /** Flattens one stack into the fields a menu assertion is written against. */
    private static InventorySnapshot.Item describe(int slot, org.bukkit.inventory.ItemStack stack) {
        String displayName = null;
        List<String> lore = List.of();
        boolean enchanted = !stack.getEnchantments().isEmpty();
        Integer customModelData = null;
        InventorySnapshot.ModelData modelData = null;

        if (stack.hasItemMeta()) {
            org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                if (meta.hasDisplayName()) {
                    displayName = legacy(meta.displayName());
                }
                if (meta.hasLore() && meta.lore() != null) {
                    lore = meta.lore().stream().map(CaptureService::legacy).toList();
                }

                enchanted = enchanted || meta.hasEnchants();
                if (meta.hasCustomModelData()) {
                    customModelData = meta.getCustomModelData();
                }
                modelData = modelDataOf(meta);
            }
        }
        return new InventorySnapshot.Item(slot, stack.getType().name(), stack.getAmount(),
                displayName, lore, enchanted, customModelData, modelData);
    }

    /** Flattens the custom model data component. */
    @SuppressWarnings("unchecked")
    private static InventorySnapshot.ModelData modelDataOf(
            org.bukkit.inventory.meta.ItemMeta meta) {
        try {
            if (!(boolean) meta.getClass().getMethod("hasCustomModelDataComponent").invoke(meta)) {
                return null;
            }
            Object component =
                    meta.getClass().getMethod("getCustomModelDataComponent").invoke(meta);
            if (component == null) {
                return null;
            }

            List<String> colors = new java.util.ArrayList<>();
            for (Object color : (List<Object>) read(component, "getColors")) {

                colors.add(hex((org.bukkit.Color) color));
            }

            InventorySnapshot.ModelData data = new InventorySnapshot.ModelData(
                    (List<Float>) read(component, "getFloats"),
                    (List<Boolean>) read(component, "getFlags"),
                    (List<String>) read(component, "getStrings"),
                    colors);
            return data.carriesNothing() ? null : data;
        } catch (ReflectiveOperationException | RuntimeException olderServer) {

            return null;
        }
    }

    private static List<?> read(Object component, String getter)
            throws ReflectiveOperationException {
        Object value = component.getClass().getMethod(getter).invoke(component);
        return value == null ? List.of() : (List<?>) value;
    }

    /** A colour as {@code #RRGGBB}, which is how a pack author writes it. */
    private static String hex(org.bukkit.Color color) {
        return String.format("#%06X", color.asRGB());
    }

    /** A component as the {@code §}-coded string a plugin author would have written. */
    private static String legacy(net.kyori.adventure.text.Component component) {
        return component == null ? null
                : net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacySection().serialize(component);
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

    /** Runs something on the server's main thread and waits for it. */
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
