package moe.vitamin.minecraft.mcp.bot.runner;

import moe.vitamin.minecraft.mcp.bot.spi.Position;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.packet.Packet;

/** Where the bot is, and what a client has to send about it. */
final class PlayerSync {

    private PlayerSync() {}

    /** The position carried by {@code packet}, or {@code null} if it is not a position packet. */
    static Position positionOf(Packet packet) {
        if (!(packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                .entity.player.ClientboundPlayerPositionPacket position)) {
            return null;
        }
        var at = position.getPosition();
        return new Position(at.getX(), at.getY(), at.getZ());
    }

    /** Replies with the teleport id, which is what unblocks everything the player sends next. */
    static void confirmTeleport(Session session, Packet packet) {
        if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                .entity.player.ClientboundPlayerPositionPacket position) {
            session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound
                    .level.ServerboundAcceptTeleportationPacket(position.getId()));
        }
    }

    /** Declares the client has finished loading. */
    static void sendLoaded(Session session) {
        session.send(org.geysermc.mcprotocollib.protocol.packet.ingame
                .serverbound.ServerboundPlayerLoadedPacket.INSTANCE);
    }

    /** Reports standing still at {@code at}, which is what a client sends every tick. */
    static void sendStanding(Session session, Position at) {
        session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player
                .ServerboundMovePlayerPosPacket(true, false, at.x(), at.y(), at.z()));
    }

    /** Moves to a position. */
    static void sendMove(Session session, double x, double y, double z) {
        session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player
                .ServerboundMovePlayerPosPacket(true, false, x, y, z));
    }
}
