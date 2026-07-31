package moe.vitamin.minecraft.mcp.bot.runner;

import moe.vitamin.minecraft.mcp.bot.spi.Position;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.packet.Packet;

/**
 * Where the bot is, on protocol 767.
 *
 * <p>Overrides the shared copy, for the three differences this range has:
 *
 * <ul>
 *   <li>the position packet carries three doubles and a {@code teleportId}, not a vector and an
 *       {@code id} — the rework landed in 1.21.2;
 *   <li>the movement packet has no horizontal-collision flag, which was added at the same time;
 *   <li>there is no {@code ServerboundPlayerLoadedPacket}. It arrived in 1.21.4, and before it
 *       the server does not hold a joining player in a loading state, so there is nothing to
 *       send and nothing to wait for.
 * </ul>
 */
final class PlayerSync {

    private PlayerSync() {}

    static Position positionOf(Packet packet) {
        if (!(packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                .entity.player.ClientboundPlayerPositionPacket position)) {
            return null;
        }
        return new Position(position.getX(), position.getY(), position.getZ());
    }

    static void confirmTeleport(Session session, Packet packet) {
        if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                .entity.player.ClientboundPlayerPositionPacket position) {
            session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound
                    .level.ServerboundAcceptTeleportationPacket(position.getTeleportId()));
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
                .ServerboundMovePlayerPosPacket(true, at.x(), at.y(), at.z()));
    }

    static void sendMove(Session session, double x, double y, double z) {
        session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player
                .ServerboundMovePlayerPosPacket(true, x, y, z));
    }
}
