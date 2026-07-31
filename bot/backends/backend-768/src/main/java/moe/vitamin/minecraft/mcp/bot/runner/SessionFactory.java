package moe.vitamin.minecraft.mcp.bot.runner;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.tcp.TcpClientSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;

/**
 * Opening a connection, on protocol 768.
 *
 * <p>Overrides the shared copy. The session rework landed in 1.21.4, not 1.21.2: a client is
 * still a {@code TcpClientSession} built from a host and a port here, owning its own event loop,
 * with {@code connect} declared on {@code Session} rather than on a {@code ClientSession} that
 * does not exist yet.
 *
 * <p><b>Identical to backend-767's copy</b>, and deliberately a copy rather than a third shared
 * directory: it describes an API of two released versions, so it cannot drift, and a mechanism
 * for sharing two frozen files between two modules would cost more than the duplication.
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
