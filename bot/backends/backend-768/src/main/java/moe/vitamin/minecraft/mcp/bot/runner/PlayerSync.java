package moe.vitamin.minecraft.mcp.bot.runner;

import moe.vitamin.minecraft.mcp.bot.spi.Position;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.packet.Packet;

/** Where the bot is, on protocol 768. */
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

    /** Nothing to declare. */
    static void sendLoaded(Session session) {

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
