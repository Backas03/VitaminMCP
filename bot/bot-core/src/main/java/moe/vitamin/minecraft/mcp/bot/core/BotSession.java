package moe.vitamin.minecraft.mcp.bot.core;

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

    private BotSession(BotIdentity identity, ClientSession session) {
        this.identity = identity;
        this.session = session;
    }

    /**
     * Opens a session against a backend that trusts proxy forwarding.
     *
     * @param host        the server to connect to
     * @param port        the server's port
     * @param identity    who the bot claims to be
     * @param claimedHost the host the backend should believe was dialled
     * @param clientIp    the address the backend should attribute the connection to
     */
    public static BotSession open(
            String host, int port, BotIdentity identity, String claimedHost, String clientIp)
            throws Exception {

        String forwardedHost =
                ForwardingHandshake.addressField(claimedHost, clientIp, identity);

        // The payload is carried by the address itself: MCProtocolLib copies the remote
        // address's host string into the intention packet. getByAddress binds an arbitrary
        // label to a literal IP without a DNS lookup, which is what makes it possible to put
        // a string no resolver could ever answer for into a connectable address.
        java.net.InetAddress resolved = java.net.InetAddress.getByName(host);
        InetSocketAddress target = new InetSocketAddress(
                java.net.InetAddress.getByAddress(forwardedHost, resolved.getAddress()), port);

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
        session.addListener(bot.new LifecycleListener(forwardedHost));
        return bot;
    }

    /**
     * Connects and waits until the bot is in the world.
     *
     * @return this session, connected
     * @throws IllegalStateException if the server disconnected us, or the join never arrived
     */
    public BotSession connect(Duration timeout) throws InterruptedException {
        session.connect(false);

        if (!joined.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            String reason = disconnectReason.get();
            close();
            throw new IllegalStateException(reason != null
                    ? "Bot " + identity.name() + " was rejected: " + reason
                    : "Bot " + identity.name() + " did not reach the world within " + timeout);
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
     * <p>Polls the server-authoritative position rather than sleeping a fixed time: how long a
     * spawn fall takes depends on the terrain, and a fixed wait is either too short on one
     * world or wasted on another.
     *
     * @return this session, standing on something
     */
    public BotSession awaitGrounded(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        double lastY = Double.NaN;
        int stableTicks = 0;

        while (System.nanoTime() < deadline) {
            org.cloudburstmc.math.vector.Vector3d current = position;
            if (current != null) {
                if (Math.abs(current.getY() - lastY) < 1.0e-6) {
                    // Unchanged across several server updates, so it is standing rather than
                    // momentarily level mid-fall.
                    if (++stableTicks >= 5) {
                        return this;
                    }
                } else {
                    stableTicks = 0;
                    lastY = current.getY();
                }
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException(
                "Bot " + identity.name() + " never stopped moving within " + timeout);
    }

    public BotSession connect() throws InterruptedException {
        return connect(DEFAULT_LOGIN_TIMEOUT);
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

        private final String forwardedHost;

        LifecycleListener(String forwardedHost) {
            this.forwardedHost = forwardedHost;
        }

        /**
         * Replaces the handshake's hostname with the forwarded identity.
         *
         * <p>Done here rather than by disguising the address the session connects to. Both
         * would work today — MCProtocolLib copies the hostname out of the remote address — but
         * that is an implementation detail of the library, whereas the intention packet is
         * part of the protocol. Rewriting the packet says what is actually meant, and does not
         * quietly stop injecting if the library changes how it derives the host.
         */
        @Override
        public void packetSending(
                org.geysermc.mcprotocollib.network.event.session.PacketSendingEvent event) {
            if (event.getPacket() instanceof ClientIntentionPacket intention) {
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

        @Override
        public void packetReceived(org.geysermc.mcprotocollib.network.Session session, Packet packet) {
            // The join packet, not the login-success one: success means the server accepted
            // the identity, this means the player is actually in a world.
            if (packet instanceof ClientboundLoginPacket) {
                inGame = true;
            }
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

        @Override
        public void disconnected(DisconnectedEvent event) {
            inGame = false;
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
