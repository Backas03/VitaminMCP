package moe.vitamin.minecraft.mcp.bot.runner;

import moe.vitamin.minecraft.mcp.bot.core.BotIdentity;
import moe.vitamin.minecraft.mcp.bot.core.ForwardingHandshake;
import moe.vitamin.minecraft.mcp.bot.spi.BossBar;
import moe.vitamin.minecraft.mcp.bot.spi.MenuItem;
import moe.vitamin.minecraft.mcp.bot.spi.Position;
import moe.vitamin.minecraft.mcp.bot.spi.Scoreboard;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.handshake.serverbound.ClientIntentionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;

/**
 * One bot's connection to a server.
 *
 * <p>Connecting is deliberately not "the TCP socket opened". A Minecraft login has several
 * more steps after that, and a bot that reports success at connect time will have tests
 * racing against a login that has not finished — or that is about to be rejected. {@link
 * #connect} waits for the play-state join packet, which is the first moment the bot exists in
 * the world as far as the server and its plugins are concerned.
 *
 * <p>Identity is injected through the handshake rather than authenticated (docs/design.md
 * §3.1): the intention packet's hostname is rewritten on the way out to carry the
 * forwarded fields.
 */
public final class BotSession implements AutoCloseable {

    /** Consecutive unchanged positions taken as "landed". */
    private static final int SETTLED_CHECKS = 5;

    /** Gap between settle checks — a server tick, since that is when position can change. */
    private static final Duration SETTLE_POLL = Duration.ofMillis(50);

    /** How long to wait for the whole handshake-login-join sequence. */
    public static final Duration DEFAULT_LOGIN_TIMEOUT = Duration.ofSeconds(30);

    private final BotIdentity identity;
    private final ClientSession session;
    private final CountDownLatch joined = new CountDownLatch(1);
    private final AtomicReference<String> disconnectReason = new AtomicReference<>();

    private volatile boolean inGame;

    /**
     * Where the server last said this bot is.
     *
     * <p>Three plain doubles, not the protocol library's vector. The position packet was reworked
     * in 1.21.2 and its vector type is not the same shape across the supported range, so holding
     * one here would spread that difference to the ticker, the settle loop, the accessors and the
     * failure message. Converted where the packet arrives — {@link PlayerSync} — the difference
     * stays in the one file that is allowed to know about it.
     */
    private volatile Position position;
    private volatile java.util.concurrent.ScheduledExecutorService ticker;
    private volatile boolean loadedSent;

    /**
     * The menu the server has opened for this bot, if any.
     *
     * <p>Tracked because a click has to name the container it is in, and the id is assigned by
     * the server when it opens the screen — there is no way to ask for it later.
     */
    private volatile int containerId = NO_CONTAINER;

    /**
     * The server's synchronisation counter for the open container.
     *
     * <p>Sent back with every click. The server uses it to notice that the client acted on a
     * stale view of the container, and a click carrying an old value is applied but immediately
     * followed by a full re-send. Tracking it is what keeps a bot's clicks from being treated as
     * a desynchronised client's.
     */
    private volatile int containerStateId;

    /** Title of the open menu, as plain text, for diagnostics and matching. */
    private volatile String containerTitle;

    /**
     * The menu's contents as the client was told them.
     *
     * <p>Kept because the server-side inventory is not always the same thing. A plugin that
     * draws its menu by sending packets — the usual approach when ProtocolLib or packetevents is
     * involved — leaves the Bukkit inventory empty and paints the screen directly. Asking the
     * server then reports an empty chest while the player is looking at a full one, and the
     * only place the truth exists is here, in what arrived on the wire.
     */
    private volatile org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack[] containerItems
            = new org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack[0];

    /** Most recent messages the server sent this bot, oldest first. */
    private final java.util.Deque<String> messages = new java.util.concurrent.ConcurrentLinkedDeque<>();

    /**
     * How many messages to keep.
     *
     * <p>Enough for the join sequence plus whatever a command replies, and bounded because a bot
     * left connected to a busy server would otherwise accumulate every public chat line for as
     * long as it lives.
     */
    private static final int MAX_MESSAGES = 100;

    /** No menu open. The player's own inventory is container 0, so -1 is the free sentinel. */
    public static final int NO_CONTAINER = -1;

    /** No entity matched. Entity ids are non-negative, so -1 is free here too. */
    public static final int NO_ENTITY = -1;

    /**
     * An entity this bot has been told about, and where it currently is.
     *
     * <p>Only what is needed to find one again. A test names an entity by where it stands, so
     * position and type are the whole of it — the numeric id exists on the wire and nowhere a
     * scenario author could have seen it.
     */
    private record TrackedEntity(int id, String type, double x, double y, double z) {

        TrackedEntity at(double x, double y, double z) {
            return new TrackedEntity(id, type, x, y, z);
        }

        TrackedEntity moveBy(double dx, double dy, double dz) {
            return new TrackedEntity(id, type, x + dx, y + dy, z + dz);
        }

        double distanceTo(double px, double py, double pz) {
            double dx = x - px;
            double dy = y - py;
            double dz = z - pz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /** Entities the server has spawned for this bot, by entity id. */
    private final java.util.Map<Integer, TrackedEntity> entities
            = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A boss bar the server is showing this bot.
     *
     * <p>Kept as current state rather than as a message, because that is what it is: a boss bar
     * stays on screen until removed, and asking "is it showing, and what does it say" is a
     * different question from "what was I told". Plugins use them for timers, event progress and
     * region names, none of which appear anywhere the agent can see.
     */
    // The record itself is moe.vitamin.minecraft.mcp.bot.spi.BossBar: what the client would draw
    // is the same shape whoever is asking, and defining it twice would mean converting between
    // two identical records on the way out.

    /** Boss bars currently shown, by the id the server addresses them with. */
    private final java.util.Map<java.util.UUID, BossBar> bossBars
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** Objective name to the title the client would draw above the sidebar. */
    private final java.util.Map<String, String> objectiveTitles
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** One scoreboard line: the number on the right, and the text the client draws. */
    private record ScoreLine(int value, String text) {}

    /** Objective name to its lines, keyed by the entry each line belongs to. */
    private final java.util.Map<String, java.util.Map<String, ScoreLine>> objectiveLines
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** Which objective is in the sidebar slot, or null when none is. */
    private volatile String sidebarObjective;

    /**
     * The text a team wraps its members' names in.
     *
     * <p>Needed because of how scoreboards are actually written. A sidebar line's "entry" is the
     * key, not the text: servers register one blank-looking entry per line — a colour code, since
     * entries must be unique — and put the words the player reads in that entry's team prefix and
     * suffix. Read the scores alone and a fifteen-line scoreboard comes back as
     * {@code ["§e", "§d", "§c", ...]}, which looks like data and is not.
     */
    private record TeamText(String prefix, String suffix) {}

    /** Team name to its prefix and suffix. */
    private final java.util.Map<String, TeamText> teamText
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** Scoreboard entry to the team it belongs to. */
    private final java.util.Map<String, String> entryTeam
            = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The nearest tracked entity to a point, or {@link #NO_ENTITY}.
     *
     * <p>Nearest rather than first, so that two NPCs standing close together resolve to the one
     * actually named. {@code type} is matched case-insensitively against the entity type and
     * ignored when blank — most useful for a Citizens NPC, which is a {@code PLAYER} standing
     * among mobs.
     */
    public int entityNear(double x, double y, double z, double radius, String type) {
        TrackedEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (TrackedEntity candidate : entities.values()) {
            if (type != null && !type.isBlank()
                    && !candidate.type().equalsIgnoreCase(type.trim())) {
                continue;
            }
            double distance = candidate.distanceTo(x, y, z);
            if (distance <= radius && distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best == null ? NO_ENTITY : best.id();
    }

    /**
     * What is actually near a point, for when {@link #entityNear} found nothing.
     *
     * <p>"No entity within 2 blocks of x/y/z" leaves the author guessing between a wrong
     * coordinate, a radius that is too small, and an NPC the bot cannot see because it is out of
     * render distance. Listing what is there answers all three at once.
     */
    public String describeEntitiesNear(double x, double y, double z, double radius) {
        return entities.values().stream()
                .filter(e -> e.distanceTo(x, y, z) <= Math.max(radius * 4, 16))
                .sorted(java.util.Comparator.comparingDouble(e -> e.distanceTo(x, y, z)))
                .limit(8)
                .map(e -> String.format(java.util.Locale.ROOT, "%s at %.1f %.1f %.1f (%.1f away)",
                        e.type(), e.x(), e.y(), e.z(), e.distanceTo(x, y, z)))
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private BotSession(BotIdentity identity, ClientSession session) {
        this.identity = identity;
        this.session = session;
    }

    /**
     * Opens a session, presenting the connection as what it actually is.
     *
     * <p>The backend is told the host that was really dialled and the address this machine
     * really connects from. Use {@link #open(String, int, BotIdentity, String, String)} to claim
     * something else.
     */
    public static BotSession open(String host, int port, BotIdentity identity) throws Exception {
        return open(host, port, identity, null, null);
    }

    /**
     * Opens a session against a backend that trusts proxy forwarding.
     *
     * @param host        the server to connect to
     * @param port        the server's port
     * @param identity    who the bot claims to be
     * @param claimedHost the host the backend should believe was dialled, or {@code null} for
     *                    {@code host} — which is what a real client dialling it would send, and
     *                    what a server routing on forced hosts matches against
     * @param clientIp    the address the backend should attribute the connection to, or
     *                    {@code null} for the one this machine really uses. Worth setting only
     *                    to reproduce a specific address — an IP ban, a geo lookup — because a
     *                    made-up one is a lie the rest of the test then has to live with
     */
    public static BotSession open(
            String host, int port, BotIdentity identity, String claimedHost, String clientIp)
            throws Exception {

        // Resolved here rather than left to connect(), so a host that does not exist says so
        // instead of arriving later as a login that never completed.
        InetSocketAddress target =
                new InetSocketAddress(java.net.InetAddress.getByName(host), port);

        // The username here still matters: the backend takes the UUID and skin from the
        // forwarded fields but the name from the login packet, and a mismatch between them is
        // the kind of thing that only shows up later as a confusing permissions result.
        MinecraftProtocol protocol = new MinecraftProtocol(identity.name());

        ClientSession session = new ClientNetworkSession(
                target,
                protocol,
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "bot-" + identity.name());
                    thread.setDaemon(true);
                    return thread;
                }),
                null,
                null);

        BotSession bot = new BotSession(identity, session);
        session.addListener(bot.new LifecycleListener(
                isBlank(claimedHost) ? host : claimedHost,
                isBlank(clientIp) ? null : clientIp));
        return bot;
    }

    /** Absent and empty mean the same thing — the runner protocol sends an empty field. */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Connects and waits until the bot is in the world.
     *
     * @return this session, connected
     * @throws IllegalStateException if the server disconnected us, or the join never arrived
     */
    public BotSession connect(Duration timeout) throws InterruptedException {
        session.connect(false);

        boolean released = joined.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        String reason = disconnectReason.get();

        // The latch is released by *either* arriving in the world or being disconnected, so it
        // alone does not mean success. Treating it as success let a rejected bot report
        // "never settled" from a later call instead of the reason the server actually gave —
        // which turned a plain protocol mismatch into a mystery.
        if (reason != null || position == null) {
            close();
            throw new IllegalStateException(reason != null
                    ? "Bot " + identity.name() + " was rejected: " + reason
                    : "Bot " + identity.name() + " did not reach the world within " + timeout
                            + (released ? " (connected, but no position ever arrived)" : ""));
        }
        startTicking();
        return this;
    }

    /**
     * Starts behaving like a client.
     *
     * <p>A real client sends its position every tick, and the server relies on that. Without
     * it the player is never processed as moving, never falls, never lands, and stays wherever
     * it was put — which is usually the air above the spawn point. Everything downstream then
     * fails in confusing ways: block actions target air, reach checks fail against a position
     * the server no longer agrees with, and none of it produces an error.
     *
     * <p>This is the difference between "the bot connected" and "the bot is a player".
     */
    private void startTicking() {
        java.util.concurrent.ScheduledExecutorService executor =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "bot-tick-" + identity.name());
                    thread.setDaemon(true);
                    return thread;
                });

        executor.scheduleAtFixedRate(() -> {
            try {
                Position current = position;
                if (current != null && session.isConnected()) {
                    // Reporting the position the server last gave us, with onGround true, is
                    // what a client does when standing still; the server applies gravity and
                    // corrects us with a teleport if we are wrong, which updates `position`.
                    PlayerSync.sendStanding(session, current);
                }
            } catch (RuntimeException ignored) {
                // A tick that fails must not kill the scheduler and silently stop the bot.
            }
        }, 0, 50, TimeUnit.MILLISECONDS);

        this.ticker = executor;
    }

    /**
     * Waits until the bot has stopped falling.
     *
     * <p>Landing is not something the protocol announces, and it cannot be asked of the server
     * either: {@code isOnGround} for a player is whatever the client last claimed, and this
     * client claims it every tick — reading it back would be reading our own assertion.
     *
     * <p>So the observable used is the one thing the server does control: it corrects a
     * client's position when it disagrees, and those corrections are what stop arriving once
     * the player is at rest. Waiting for the correction stream to settle is therefore waiting
     * for the server to agree about where the bot is, which is the real precondition for
     * acting on coordinates.
     *
     * <p>This is a poll, and unlike the others it cannot be replaced by an agent-side
     * predicate, because the fact being waited on lives in this process rather than on the
     * server.
     *
     * @param timeout how long to allow for the fall
     * @throws IllegalStateException if the position never settles
     */
    public BotSession awaitGrounded(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        double lastY = Double.NaN;
        int settledChecks = 0;

        while (System.nanoTime() < deadline) {
            Position current = position;
            if (current != null) {
                if (Math.abs(current.y() - lastY) < 1.0e-6) {
                    // Several checks, not one: a fall passes through frames where Y happens to
                    // repeat, and one sample would call those landed.
                    if (++settledChecks >= SETTLED_CHECKS) {
                        return this;
                    }
                } else {
                    settledChecks = 0;
                    lastY = current.y();
                }
            }
            Thread.sleep(SETTLE_POLL.toMillis());
        }
        throw new IllegalStateException("Bot " + identity.name()
                + " never settled within " + timeout + "; last position " + describePosition());
    }

    /** Whether the bot has joined the world, not merely opened a socket. */
    public boolean isInGame() {
        return inGame && session.isConnected();
    }

    public BotIdentity identity() {
        return identity;
    }

    /** The underlying session, for sending packets. */
    public ClientSession session() {
        return session;
    }

    /** Where the server last said this bot is, or {@code null} before the first position. */
    public Position position() {
        return position;
    }

    public double x() {
        Position at = position;
        return at == null ? 0 : at.x();
    }

    public double y() {
        Position at = position;
        return at == null ? 0 : at.y();
    }

    public double z() {
        Position at = position;
        return at == null ? 0 : at.z();
    }

    /** The block position the bot is standing in. */
    public int blockX() {
        return (int) Math.floor(x());
    }

    public int blockY() {
        return (int) Math.floor(y());
    }

    public int blockZ() {
        return (int) Math.floor(z());
    }

    /** The open menu's container id, or {@link #NO_CONTAINER}. */
    public int containerId() {
        return containerId;
    }

    /** The server's current synchronisation counter for the open container. */
    public int containerStateId() {
        return containerStateId;
    }

    /** The open menu's title as plain text, or {@code null} if no menu is open. */
    public String containerTitle() {
        return containerTitle;
    }

    /** Whether the server has a menu open for this bot. */
    public boolean hasMenuOpen() {
        return containerId != NO_CONTAINER;
    }

    /**
     * Forgets the open menu, because this client is the one closing it.
     *
     * <p>The server does not echo a close the client asked for — it acts on it and says nothing.
     * So a bot that sends the packet and keeps its own record would go on believing the screen
     * is up, and the next click would be addressed to a container that is no longer open. A real
     * client clears its state at exactly this point, and so does this one.
     */
    void forgetContainer() {
        containerId = NO_CONTAINER;
        containerTitle = null;
        containerItems = new org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack[0];
    }

    /**
     * The open menu's slots as the client received them, one entry per occupied slot.
     *
     * <p><b>The item is a numeric id, not a name.</b> The protocol carries a registry index and
     * MCProtocolLib ships no table to turn it back into {@code DIAMOND_SWORD}; the mapping lives
     * in the game jar. The agent's own {@code state_query} gives real material names — this is
     * for the case where that comes back empty because the menu was never in the server's
     * inventory to begin with. Names, lore and model data do come through, and for a menu those
     * are what identify a button anyway.
     */
    public List<MenuItem> clientMenuItems() {
        var items = containerItems;
        List<MenuItem> out = new ArrayList<>();
        for (int slot = 0; slot < items.length; slot++) {
            var item = items[slot];
            if (item == null) {
                continue;
            }
            out.add(new MenuItem(slot, item.getId(), item.getAmount(),
                    nameOf(item), modelDataOf(item), loreOf(item)));
        }
        return out;
    }

    private static String nameOf(
            org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack item) {
        var components = item.getDataComponentsPatch();
        if (components == null) {
            return "";
        }
        var custom = components.get(
                org.geysermc.mcprotocollib.protocol.data.game.item.component
                        .DataComponentTypes.CUSTOM_NAME);
        if (custom == null) {
            custom = components.get(
                    org.geysermc.mcprotocollib.protocol.data.game.item.component
                            .DataComponentTypes.ITEM_NAME);
        }
        return custom == null ? "" : PlainTextComponentSerializer.plainText().serialize(custom);
    }

    private static String loreOf(
            org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack item) {
        var components = item.getDataComponentsPatch();
        if (components == null) {
            return "";
        }
        var lore = components.get(
                org.geysermc.mcprotocollib.protocol.data.game.item.component
                        .DataComponentTypes.LORE);
        if (lore == null || lore.isEmpty()) {
            return "";
        }
        return lore.stream()
                .map(line -> PlainTextComponentSerializer.plainText().serialize(line))
                .collect(java.util.stream.Collectors.joining(" | "));
    }

    /** @return the first float of the model data component, or empty when it carries none */
    private static String modelDataOf(
            org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack item) {
        var components = item.getDataComponentsPatch();
        if (components == null) {
            return "";
        }
        var model = components.get(
                org.geysermc.mcprotocollib.protocol.data.game.item.component
                        .DataComponentTypes.CUSTOM_MODEL_DATA);
        if (model == null) {
            return "";
        }
        // Strings first: a pack keyed on them is the modern idiom, and the floats are often
        // absent entirely when it is.
        if (model.strings() != null && !model.strings().isEmpty()) {
            return String.join(",", model.strings());
        }
        if (model.floats() != null && !model.floats().isEmpty()) {
            return String.valueOf(model.floats().get(0));
        }
        return "";
    }

    /** Messages the server sent this bot, oldest first. */
    public List<String> receivedMessages() {
        return List.copyOf(messages);
    }

    /** Boss bars on screen now. */
    public List<BossBar> clientBossBars() {
        return List.copyOf(bossBars.values());
    }

    /**
     * The sidebar scoreboard, or {@code null} when nothing is displayed there.
     *
     * <p>Lines are sorted highest score first, here rather than by the caller, because the sort
     * is part of what the player sees: the scores are a layout instruction, not data anyone
     * reads.
     */
    public Scoreboard clientScoreboard() {
        String objective = sidebarObjective;
        if (objective == null) {
            return null;
        }
        var lines = objectiveLines.get(objective);
        List<String> rendered = lines == null ? List.of() : lines.entrySet().stream()
                .sorted(java.util.Comparator.comparingInt(
                        (java.util.Map.Entry<String, ScoreLine> e) -> e.getValue().value())
                        .reversed())
                .map(entry -> renderLine(entry.getKey(), entry.getValue()))
                .toList();
        return new Scoreboard(objectiveTitles.getOrDefault(objective, objective), rendered);
    }

    /**
     * One sidebar line as the client would draw it.
     *
     * <p>The entry is wrapped in its team's prefix and suffix, which is where the words live on
     * every scoreboard written since teams became the way to do it. Since 1.20.3 a score may
     * instead carry its own display text, and that wins outright — it is the whole line, not
     * something to wrap.
     */
    private String renderLine(String entry, ScoreLine line) {
        if (!line.text().equals(entry)) {
            return line.text();
        }
        String team = entryTeam.get(entry);
        TeamText text = team == null ? null : teamText.get(team);
        if (text == null) {
            return stripLegacyCodes(entry);
        }
        return text.prefix() + stripLegacyCodes(entry) + text.suffix();
    }

    /**
     * Drops legacy section-sign codes, which are formatting rather than text.
     *
     * <p>Applied to the entry only. The client reads {@code §e} as "colour the rest yellow" and
     * draws nothing for it, so leaving it in makes every line of a blank-entry scoreboard look
     * like it ends in stray characters. Prefix and suffix have already been through the plain
     * text serializer and carry no codes to remove.
     */
    private static String stripLegacyCodes(String entry) {
        return entry.replaceAll("(?i)§[0-9a-fk-or]", "");
    }

    /** A readable position, for failure messages. */
    public String describePosition() {
        return position == null ? "unknown" : x() + ", " + y() + ", " + z();
    }

    /** Actions this bot can perform. */
    public BotActions actions() {
        return new BotActions(this);
    }

    /** Why the server disconnected this bot, if it did. */
    public String disconnectReason() {
        return disconnectReason.get();
    }

    @Override
    public void close() {
        java.util.concurrent.ScheduledExecutorService executor = ticker;
        if (executor != null) {
            // Stopped before disconnecting, so the last tick cannot fire against a closed
            // session and log a spurious failure.
            executor.shutdownNow();
            ticker = null;
        }
        if (session.isConnected()) {
            session.disconnect("Bot session closed");
        }
        inGame = false;
    }

    /** Injects the forwarded identity, and tracks how far through login the bot has got. */
    private final class LifecycleListener extends SessionAdapter {

        private final String claimedHost;
        private final String clientIp;

        /** @param clientIp {@code null} to report the address the socket really uses */
        LifecycleListener(String claimedHost, String clientIp) {
            this.claimedHost = claimedHost;
            this.clientIp = clientIp;
        }

        /**
         * Replaces the handshake's hostname with the forwarded identity.
         *
         * <p>Done by rewriting the packet rather than by disguising the address the session
         * connects to. Both would work — MCProtocolLib copies the hostname out of the remote
         * address, so a fake label on it ends up on the wire — but that is an implementation
         * detail of the library, whereas the intention packet is part of the protocol.
         * Rewriting the packet says what is actually meant, and does not quietly stop injecting
         * if the library changes how it derives the host.
         *
         * <p>The field is assembled here, not at construction, because until the socket is
         * connected there is no local address to report — and the handshake is the first thing
         * sent after it connects, so here is the earliest moment it is known.
         */
        @Override
        public void packetSending(
                org.geysermc.mcprotocollib.network.event.session.PacketSendingEvent event) {
            if (event.getPacket() instanceof ClientIntentionPacket intention) {
                String forwardedHost = ForwardingHandshake.addressField(
                        claimedHost, clientIp == null ? localAddress() : clientIp, identity);

                if (Boolean.getBoolean("vitaminmcp.debugHandshake")) {
                    System.err.println("[handshake] protocol=" + intention.getProtocolVersion()
                            + " intent=" + intention.getIntent()
                            + " originalHost=" + intention.getHostname()
                            + " fields=" + ForwardingHandshake.parse(forwardedHost).length
                            + " injected=" + forwardedHost.replace('\0', '|'));
                }
                event.setPacket(new ClientIntentionPacket(
                        intention.getProtocolVersion(),
                        forwardedHost,
                        intention.getPort(),
                        intention.getIntent()));
            }
        }

        /**
         * The address this machine is connecting from.
         *
         * <p>What the server would have seen had nothing been forwarding on our behalf — so
         * reporting it means the bot looks like a client at this machine rather than at a
         * fictional loopback address, which is what anything keyed on the address (bans,
         * per-IP connection limits, geo lookups) then sees.
         *
         * <p>Exact when nothing translates addresses between here and the server, and close
         * enough to be useful when something does; a NAT would have the server seeing the
         * public address instead, which no client can determine for itself. Pass an explicit
         * clientIp when the test needs a specific one.
         */
        private String localAddress() {
            if (session.getLocalAddress() instanceof InetSocketAddress local
                    && local.getAddress() != null) {
                return local.getAddress().getHostAddress();
            }
            // Only reachable if the channel has no local address, which for a socket that is
            // mid-handshake means it is already gone. The connection is about to fail either
            // way; loopback keeps the field well-formed so it fails with the server's reason
            // rather than an exception on a Netty thread.
            return "127.0.0.1";
        }

        @Override
        public void packetReceived(org.geysermc.mcprotocollib.network.Session session, Packet packet) {
            // The join packet, not the login-success one: success means the server accepted
            // the identity, this means the player is actually in a world.
            if (packet instanceof ClientboundLoginPacket) {
                inGame = true;
                // Joining a world invalidates every id from the previous one. Keeping them would
                // let a lookup match an entity that is no longer anywhere. The same applies to
                // the screen: boss bars and scoreboards are re-sent on join, so anything held
                // from before would be shown alongside its own replacement.
                entities.clear();
                bossBars.clear();
                objectiveTitles.clear();
                objectiveLines.clear();
                teamText.clear();
                entryTeam.clear();
                sidebarObjective = null;
            }

            trackContainer(packet);
            trackMessages(packet);
            trackEntities(packet);
            trackBossBars(packet);
            trackScoreboard(packet);
            // The join packet does not say where the player is; the server follows it with a
            // position. Waiting for that too means a bot is only "ready" once it knows where it
            // stands, which is what any action needing coordinates depends on.
            Position arrived = PlayerSync.positionOf(packet);
            if (arrived != null) {
                BotSession.this.position = arrived;

                // The server will not act on anything this player sends until the teleport is
                // acknowledged — a real client replies with the id it was given, and until it
                // does, block actions and movement are dropped without a word. Skipping this
                // is why the first bot appeared to connect fine and then do nothing.
                PlayerSync.confirmTeleport(session, packet);

                // The client declares it has finished loading and is ready to play. Until that
                // arrives the server holds the player in a loading state and ignores world
                // interactions — silently, so a bot that skips it connects, stands there, and
                // has every dig discarded with no event and no error. This was the actual cause
                // of BlockBreakEvent never appearing.
                if (!loadedSent) {
                    loadedSent = true;
                    PlayerSync.sendLoaded(session);
                }

                joined.countDown();
            }
        }

        /**
         * Follows the open menu and its synchronisation counter.
         *
         * <p>The state id arrives on three different packets and every one of them advances it.
         * Missing any means the next click carries a stale id, which the server answers by
         * resending the whole container — harmless once, but it makes a test that clicks twice
         * behave differently from one that clicks once, for reasons nothing reports.
         */
        private void trackContainer(Packet packet) {
            if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                    .inventory.ClientboundOpenScreenPacket open) {
                containerId = open.getContainerId();
                containerTitle = PlainTextComponentSerializer.plainText()
                        .serialize(open.getTitle());
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.inventory.ClientboundContainerSetContentPacket content) {
                containerStateId = content.getStateId();
                containerItems = content.getItems();
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.inventory.ClientboundContainerSetSlotPacket slot) {
                containerStateId = slot.getStateId();
                // One slot at a time is how a menu is usually filled after it opens, so
                // following only the full sends would show a permanently empty screen.
                var current = containerItems;
                if (slot.getSlot() >= 0 && slot.getSlot() < current.length) {
                    var updated = java.util.Arrays.copyOf(current, current.length);
                    updated[slot.getSlot()] = slot.getItem();
                    containerItems = updated;
                }
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.inventory.ClientboundContainerClosePacket) {
                // The server can close a menu on its own — a plugin moving the player to
                // another screen, or rejecting what they did. Forgetting it here means a later
                // click reports "no menu open" rather than being sent into a container that is
                // no longer there.
                containerId = NO_CONTAINER;
                containerTitle = null;
            }
        }

        /**
         * Labels a non-chat message with where it appeared, or drops it if it says nothing.
         *
         * <p>Clearing the action bar is done by sending an empty one, and a scenario that
         * asserted on messages would otherwise accumulate a blank entry every time any plugin
         * did that.
         */
        /** A component as plain text, treating absent as empty. */
        private static String plain(net.kyori.adventure.text.Component component) {
            return component == null
                    ? ""
                    : PlainTextComponentSerializer.plainText().serialize(component);
        }

        private static String overlay(String where, net.kyori.adventure.text.Component component) {
            String plain = PlainTextComponentSerializer.plainText().serialize(component);
            return plain.isBlank() ? null : "[" + where + "] " + plain;
        }

        /**
         * Follows the boss bars on this bot's screen.
         *
         * <p>The server sends one packet per change rather than the whole bar each time, so the
         * current state only exists by accumulating them — which is exactly why nothing else can
         * answer "what does the boss bar say right now".
         */
        private void trackBossBars(Packet packet) {
            if (!(packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                    .ClientboundBossEventPacket event)) {
                return;
            }
            switch (event.getAction()) {
                case ADD -> bossBars.put(event.getUuid(), new BossBar(
                        PlainTextComponentSerializer.plainText().serialize(event.getTitle()),
                        event.getHealth(),
                        event.getColor() == null ? "" : event.getColor().name()));
                case REMOVE -> bossBars.remove(event.getUuid());
                case UPDATE_TITLE -> bossBars.computeIfPresent(event.getUuid(), (id, bar) ->
                        new BossBar(
                                PlainTextComponentSerializer.plainText()
                                        .serialize(event.getTitle()),
                                bar.progress(), bar.color()));
                case UPDATE_HEALTH -> bossBars.computeIfPresent(event.getUuid(), (id, bar) ->
                        new BossBar(bar.title(), event.getHealth(), bar.color()));
                case UPDATE_STYLE -> bossBars.computeIfPresent(event.getUuid(), (id, bar) ->
                        new BossBar(bar.title(), bar.progress(),
                                event.getColor() == null ? "" : event.getColor().name()));
                // UPDATE_FLAGS carries darken-sky and boss-music, which nothing here reads.
                default -> { }
            }
        }

        /**
         * Follows the sidebar scoreboard.
         *
         * <p>Assembled from three packet types, none of which is the whole picture: one names
         * the objective, one puts an objective in a display slot, and one sets a single line.
         * A scoreboard is the most common place a server puts a player's live state — money,
         * region, quest progress — and it reaches the client only, so this is the only side that
         * can be asked what it says.
         *
         * <p>Only the sidebar is followed. The player-list and below-name slots hold numbers
         * rather than the lines anyone writes a test about.
         */
        private void trackScoreboard(Packet packet) {
            if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                    .scoreboard.ClientboundSetObjectivePacket objective) {
                switch (objective.getAction()) {
                    case ADD, UPDATE -> objectiveTitles.put(objective.getName(),
                            objective.getDisplayName() == null
                                    ? objective.getName()
                                    : PlainTextComponentSerializer.plainText()
                                            .serialize(objective.getDisplayName()));
                    case REMOVE -> {
                        objectiveTitles.remove(objective.getName());
                        objectiveLines.remove(objective.getName());
                    }
                    default -> { }
                }
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.scoreboard.ClientboundSetDisplayObjectivePacket display) {
                if (display.getPosition() == org.geysermc.mcprotocollib.protocol.data.game
                        .scoreboard.ScoreboardPosition.SIDEBAR) {
                    // An empty name clears the slot, which is how a plugin hides its scoreboard.
                    sidebarObjective = display.getName() == null || display.getName().isEmpty()
                            ? null
                            : display.getName();
                }
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.scoreboard.ClientboundSetScorePacket score) {
                // Since 1.20.3 a line can carry its own text; before that the entry name was the
                // line, which is why servers used to fill scoreboards with colour-code entries.
                String line = score.getDisplay() == null
                        ? score.getOwner()
                        : PlainTextComponentSerializer.plainText().serialize(score.getDisplay());
                objectiveLines
                        .computeIfAbsent(score.getObjective(),
                                key -> new java.util.concurrent.ConcurrentHashMap<>())
                        .put(score.getOwner(), new ScoreLine(score.getValue(), line));
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.scoreboard.ClientboundResetScorePacket reset) {
                var lines = objectiveLines.get(reset.getObjective());
                if (lines != null) {
                    lines.remove(reset.getOwner());
                }
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.scoreboard.ClientboundSetPlayerTeamPacket team) {
                switch (team.getAction()) {
                    case CREATE, UPDATE -> {
                        teamText.put(team.getTeamName(), new TeamText(
                                plain(team.getPrefix()), plain(team.getSuffix())));
                        // CREATE carries members; UPDATE does not, and passes null.
                        if (team.getPlayers() != null) {
                            for (String entry : team.getPlayers()) {
                                entryTeam.put(entry, team.getTeamName());
                            }
                        }
                    }
                    case ADD_PLAYER -> {
                        for (String entry : team.getPlayers()) {
                            entryTeam.put(entry, team.getTeamName());
                        }
                    }
                    case REMOVE_PLAYER -> {
                        for (String entry : team.getPlayers()) {
                            entryTeam.remove(entry, team.getTeamName());
                        }
                    }
                    case REMOVE -> {
                        teamText.remove(team.getTeamName());
                        entryTeam.values().remove(team.getTeamName());
                    }
                    default -> { }
                }
            }
        }

        /**
         * Follows the entities the server has told this bot about.
         *
         * <p>Needed because the protocol addresses an entity by a numeric id the server invents,
         * and nothing outside this client ever sees it. A scenario says "the NPC at these
         * coordinates"; only the tracker can turn that into the id an interact packet carries.
         *
         * <p>Position is followed through teleports and relative moves as well as the initial
         * spawn. A stationary NPC needs none of that, but a wandering one would otherwise be
         * looked up at where it first appeared, and the resulting miss reads as "no entity
         * there" rather than as stale data.
         */
        private void trackEntities(Packet packet) {
            if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                    .entity.ClientboundAddEntityPacket add) {
                entities.put(add.getEntityId(), new TrackedEntity(
                        add.getEntityId(),
                        add.getType() == null ? "" : add.getType().toString(),
                        add.getX(), add.getY(), add.getZ()));
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.entity.ClientboundRemoveEntitiesPacket remove) {
                for (int id : remove.getEntityIds()) {
                    entities.remove(id);
                }
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.entity.ClientboundTeleportEntityPacket teleport) {
                entities.computeIfPresent(teleport.getId(), (id, known) -> known.at(
                        teleport.getPosition().getX(),
                        teleport.getPosition().getY(),
                        teleport.getPosition().getZ()));
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.entity.ClientboundMoveEntityPosPacket move) {
                entities.computeIfPresent(move.getEntityId(), (id, known) -> known.moveBy(
                        move.getMoveX(), move.getMoveY(), move.getMoveZ()));
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.entity.ClientboundMoveEntityPosRotPacket move) {
                entities.computeIfPresent(move.getEntityId(), (id, known) -> known.moveBy(
                        move.getMoveX(), move.getMoveY(), move.getMoveZ()));
            }
        }

        /**
         * Keeps what the server said to this bot.
         *
         * <p>Almost every plugin refusal is a message and nothing else — no exception, no console
         * line, no event. Without this, a command that was declined for lack of permission and
         * one that silently did nothing are indistinguishable from the server side, which is
         * exactly the wall this hit on a real server.
         *
         * <p>Three chat packet types because the server picks between them by how the message was
         * produced: plugins and command feedback use the system one, player chat the signed one,
         * and anything relayed on a player's behalf the disguised one. A test does not care
         * which, so they all land in the same place.
         *
         * <p>The action bar and the title are here for the same reason the chat ones are, and
         * were added after a refusal went missing. A plugin declining an interaction had put its
         * reason above the hotbar rather than in chat, which is an ordinary thing to do and left
         * {@code messages} showing nothing at all — indistinguishable from a plugin that never
         * ran. They carry a prefix so the reader knows where the player would have seen it,
         * since "above the hotbar, briefly" and "in chat, persistently" are different claims
         * about what a person would notice.
         */
        private void trackMessages(Packet packet) {
            String text = null;
            if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                    .ClientboundSystemChatPacket system) {
                text = PlainTextComponentSerializer.plainText().serialize(system.getContent());
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.ClientboundDisguisedChatPacket disguised) {
                text = PlainTextComponentSerializer.plainText().serialize(disguised.getMessage());
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.ClientboundPlayerChatPacket chat) {
                text = chat.getContent();
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.title.ClientboundSetActionBarTextPacket actionBar) {
                text = overlay("action bar", actionBar.getText());
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.title.ClientboundSetTitleTextPacket title) {
                text = overlay("title", title.getText());
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.title.ClientboundSetSubtitleTextPacket subtitle) {
                text = overlay("subtitle", subtitle.getText());
            }
            if (text == null) {
                return;
            }
            // Stored as it arrived. Making it safe to put on the wire is the launcher's job —
            // a backend that pre-mangled text would be answering a question about the transport
            // rather than about what the player was told.
            messages.addLast(text);
            while (messages.size() > MAX_MESSAGES) {
                messages.pollFirst();
            }
        }

        @Override
        public void disconnected(DisconnectedEvent event) {
            inGame = false;
            containerId = NO_CONTAINER;
            containerTitle = null;
            entities.clear();
            disconnectReason.set(describe(event));
            // Released so a caller waiting on login fails immediately with the server's reason
            // rather than sitting out the full timeout.
            joined.countDown();
        }

        private String describe(DisconnectedEvent event) {
            String reason = event.getReason() == null
                    ? "no reason given"
                    : PlainTextComponentSerializer.plainText().serialize(event.getReason());
            Throwable cause = event.getCause();
            return cause == null ? reason : reason + " (" + cause + ")";
        }
    }
}
