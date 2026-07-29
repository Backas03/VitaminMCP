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
        return this;
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

    /** Why the server disconnected this bot, if it did. */
    public String disconnectReason() {
        return disconnectReason.get();
    }

    @Override
    public void close() {
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
