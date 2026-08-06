package moe.vitamin.minecraft.mcp.bot.runner;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.tcp.TcpClientSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;

/** Opening a connection, on protocol 767. */
final class SessionFactory {

    private SessionFactory() {}

    static Session open(String host, int port, String username) throws Exception {
        return new TcpClientSession(host, port, new MinecraftProtocol(username));
    }

    static void connect(Session session) {
        session.connect(false);
    }
}
