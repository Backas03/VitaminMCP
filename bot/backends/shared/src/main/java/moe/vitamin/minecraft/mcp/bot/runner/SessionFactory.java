package moe.vitamin.minecraft.mcp.bot.runner;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;

/** Opening a connection, which is the part of MCProtocolLib that was rebuilt. */
final class SessionFactory {

    private SessionFactory() {}

    /** A session that has not connected yet. */
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
