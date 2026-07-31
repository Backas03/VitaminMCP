package moe.vitamin.minecraft.mcp.bot.runner;

import moe.vitamin.minecraft.mcp.bot.spi.Position;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.packet.Packet;

/**
 * Where the bot is, on protocol 768.
 *
 * <p>Overrides the shared copy for one reason only: {@code ServerboundPlayerLoadedPacket} does
 * not exist yet. It arrived in 1.21.4, and before it the server does not hold a joining player
 * in a loading state, so there is nothing to send.
 *
 * <p>The position packet and the movement packet are already the modern shape here — the rework
 * that changed them is what 1.21.2 <em>is</em> — so those methods match the shared copy exactly.
 */
final class PlayerSync {

    private PlayerSync() {}

    static Position positionOf(Packet packet) {
        if (!(packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                .entity.player.ClientboundPlayerPositionPacket position)) {
            return null;
        }
        var at = position.getPosition();
        return new Position(at.getX(), at.getY(), at.getZ());
    }

    static void confirmTeleport(Session session, Packet packet) {
        if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                .entity.player.ClientboundPlayerPositionPacket position) {
            session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound
                    .level.ServerboundAcceptTeleportationPacket(position.getId()));
        }
    }

    /**
     * Nothing to declare.
     *
     * <p>Deliberately empty rather than absent: the shared {@link BotSession} calls this once per
     * join for every version, and a version where it is a no-op is a fact about that version, not
     * a branch for the caller to carry.
     */
    static void sendLoaded(Session session) {
        // No loading handshake before 1.21.4.
    }

    static void sendStanding(Session session, Position at) {
        session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player
                .ServerboundMovePlayerPosPacket(true, false, at.x(), at.y(), at.z()));
    }

    static void sendMove(Session session, double x, double y, double z) {
        session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player
                .ServerboundMovePlayerPosPacket(true, false, x, y, z));
    }
}
