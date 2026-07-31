package moe.vitamin.minecraft.mcp.bot.runner;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.tcp.TcpClientSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;

/**
 * Opening a connection, on protocol 767.
 *
 * <p>Overrides the shared copy. Before 1.21.2 a client is a {@code TcpClientSession} built from a
 * host and a port, and it owns its own event loop — there is no {@code ClientNetworkSession} and
 * no executor to hand it. {@code connect} is declared on {@code Session} here rather than on a
 * {@code ClientSession} that does not exist.
 *
 * <p>The host is passed as text rather than resolved first, which is what this constructor takes.
 * A name that does not resolve therefore fails at connect rather than at open; the caller waits
 * for the join either way and reports the same timeout.
 */
final class SessionFactory {

    private SessionFactory() {}

    static Session open(String host, int port, String username) throws Exception {
        return new TcpClientSession(host, port, new MinecraftProtocol(username));
    }

    static void connect(Session session) {
        session.connect(false);
    }
}
