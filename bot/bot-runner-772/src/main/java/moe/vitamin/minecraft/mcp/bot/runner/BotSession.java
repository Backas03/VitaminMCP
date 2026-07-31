package moe.vitamin.minecraft.mcp.bot.runner;

import moe.vitamin.minecraft.mcp.bot.core.BotIdentity;
import moe.vitamin.minecraft.mcp.bot.core.ForwardingHandshake;
import moe.vitamin.minecraft.mcp.bot.core.RunnerProtocol;

import java.net.InetSocketAddress;
import java.time.Duration;
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
    private volatile org.cloudburstmc.math.vector.Vector3d position;
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
                org.cloudburstmc.math.vector.Vector3d current = position;
                if (current != null && session.isConnected()) {
                    // Reporting the position the server last gave us, with onGround true, is
                    // what a client does when standing still; the server applies gravity and
                    // corrects us with a teleport if we are wrong, which updates `position`.
                    session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame
                            .serverbound.player.ServerboundMovePlayerPosPacket(
                            true, false, current.getX(), current.getY(), current.getZ()));
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
            org.cloudburstmc.math.vector.Vector3d current = position;
            if (current != null) {
                if (Math.abs(current.getY() - lastY) < 1.0e-6) {
                    // Several checks, not one: a fall passes through frames where Y happens to
                    // repeat, and one sample would call those landed.
                    if (++settledChecks >= SETTLED_CHECKS) {
                        return this;
                    }
                } else {
                    settledChecks = 0;
                    lastY = current.getY();
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
    public org.cloudburstmc.math.vector.Vector3d position() {
        return position;
    }

    /**
     * The bot's position as plain numbers.
     *
     * <p>Separate from {@link #position()} because that returns a MCProtocolLib type, and
     * callers outside this module have no business resolving one. Keeping the protocol library
     * inside bot-core is what allows it to be swapped without touching anything above.
     */
    public double x() {
        org.cloudburstmc.math.vector.Vector3d at = position;
        return at == null ? 0 : at.getX();
    }

    public double y() {
        org.cloudburstmc.math.vector.Vector3d at = position;
        return at == null ? 0 : at.getY();
    }

    public double z() {
        org.cloudburstmc.math.vector.Vector3d at = position;
        return at == null ? 0 : at.getZ();
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
     * The open menu's slots as the client received them, one record per occupied slot.
     *
     * <p>{@code slot ␟ itemId ␟ amount ␟ name ␟ customModelData ␟ lore}, joined by ␞.
     *
     * <p><b>The item is a numeric id, not a name.</b> The protocol carries a registry index and
     * MCProtocolLib ships no table to turn it back into {@code DIAMOND_SWORD}; the mapping lives
     * in the game jar. The agent's own {@code state_query} gives real material names — this is
     * for the case where that comes back empty because the menu was never in the server's
     * inventory to begin with. Names, lore and model data do come through, and for a menu those
     * are what identify a button anyway.
     */
    public String clientMenuItems() {
        var items = containerItems;
        StringBuilder out = new StringBuilder();
        for (int slot = 0; slot < items.length; slot++) {
            var item = items[slot];
            if (item == null) {
                continue;
            }
            if (out.length() > 0) {
                out.append(RunnerProtocol.RECORD_SEPARATOR);
            }
            out.append(slot).append(RunnerProtocol.UNIT_SEPARATOR)
                    .append(item.getId()).append(RunnerProtocol.UNIT_SEPARATOR)
                    .append(item.getAmount()).append(RunnerProtocol.UNIT_SEPARATOR)
                    .append(RunnerProtocol.sanitize(nameOf(item))).append(RunnerProtocol.UNIT_SEPARATOR)
                    .append(modelDataOf(item)).append(RunnerProtocol.UNIT_SEPARATOR)
                    .append(RunnerProtocol.sanitize(loreOf(item)));
        }
        return out.toString();
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

    /** Messages the server sent this bot, oldest first, joined by ␞. */
    public String receivedMessages() {
        return String.join(String.valueOf(RunnerProtocol.RECORD_SEPARATOR), messages);
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
                // let a lookup match an entity that is no longer anywhere.
                entities.clear();
            }

            trackContainer(packet);
            trackMessages(packet);
            trackEntities(packet);
            // The join packet does not say where the player is; the server follows it with a
            // position. Waiting for that too means a bot is only "ready" once it knows where it
            // stands, which is what any action needing coordinates depends on.
            if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                    .entity.player.ClientboundPlayerPositionPacket position) {
                BotSession.this.position = position.getPosition();

                // The server will not act on anything this player sends until the teleport is
                // acknowledged — a real client replies with the id it was given, and until it
                // does, block actions and movement are dropped without a word. Skipping this
                // is why the first bot appeared to connect fine and then do nothing.
                session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound
                        .level.ServerboundAcceptTeleportationPacket(position.getId()));

                // Added in 1.21.4: the client declares it has finished loading and is ready to
                // play. Until it arrives the server holds the player in a loading state and
                // ignores world interactions — silently, so a bot that skips it connects,
                // stands there, and has every dig discarded with no event and no error. This
                // was the actual cause of BlockBreakEvent never appearing.
                if (!loadedSent) {
                    loadedSent = true;
                    session.send(org.geysermc.mcprotocollib.protocol.packet.ingame
                            .serverbound.ServerboundPlayerLoadedPacket.INSTANCE);
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
        private static String overlay(String where, net.kyori.adventure.text.Component component) {
            String plain = PlainTextComponentSerializer.plainText().serialize(component);
            return plain.isBlank() ? null : "[" + where + "] " + plain;
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
            messages.addLast(RunnerProtocol.sanitize(text));
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
