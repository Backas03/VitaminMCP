package moe.vitamin.minecraft.mcp.bot.runner;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;

/**
 * Opening a connection, which is the part of MCProtocolLib that was rebuilt.
 *
 * <p><b>A seam.</b> Up to 1.21.1 a client was a {@code TcpClientSession} constructed from a host
 * and a port; from 1.21.2 it is a {@code ClientNetworkSession} constructed from a socket address
 * and given its own event loop. {@code connect} moved too — it is declared on {@code Session} in
 * the older library and on {@code ClientSession} in the newer one, so calling it through the
 * common supertype does not compile on both.
 *
 * <p>Everything above returns {@link Session}, which does exist in every version and carries the
 * four things the rest of the backend needs: listeners, send, isConnected, disconnect.
 *
 * <p>This copy is the modern one: protocol 768 and later.
 */
final class SessionFactory {

    private SessionFactory() {}

    /**
     * A session that has not connected yet.
     *
     * <p>The address is resolved here rather than left to {@code connect}, so a host that does
     * not exist says so immediately instead of arriving later as a login that never completed.
     *
     * @param username the name in the login packet. The backend takes the UUID and skin from the
     *                 forwarded handshake fields but the name from here, and a mismatch between
     *                 them only shows up later as a confusing permissions result
     */
    static Session open(String host, int port, String username) throws Exception {
        InetSocketAddress target =
                new InetSocketAddress(java.net.InetAddress.getByName(host), port);

        return new ClientNetworkSession(
                target,
                new MinecraftProtocol(username),
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "bot-" + username);
                    thread.setDaemon(true);
                    return thread;
                }),
                null,
                null);
    }

    /** Starts the connection without blocking on it; the caller waits for the join. */
    static void connect(Session session) {
        ((org.geysermc.mcprotocollib.network.ClientSession) session).connect(false);
    }
}
