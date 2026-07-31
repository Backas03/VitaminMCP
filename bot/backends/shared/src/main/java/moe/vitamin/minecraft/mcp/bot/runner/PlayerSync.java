package moe.vitamin.minecraft.mcp.bot.runner;

import moe.vitamin.minecraft.mcp.bot.spi.Position;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.packet.Packet;

/**
 * Where the bot is, and what a client has to send about it.
 *
 * <p><b>This is the seam.</b> Everything in this file has changed shape inside the supported
 * range, and nothing else in the backend has:
 *
 * <ul>
 *   <li>1.21.2 reworked the position packet — it gained delta movement and its fields moved into
 *       a vector, where before they were three doubles on the packet itself;
 *   <li>1.21.2 added the horizontal-collision flag to the movement packets;
 *   <li>1.21.4 added {@code ServerboundPlayerLoadedPacket}. Before it, the server did not hold a
 *       joining player in a loading state and nothing needed to be sent.
 * </ul>
 *
 * <p>A backend whose library differs at any of those points drops its own copy of this file at
 * the same path and leaves the rest of the shared source alone (docs/multi-version.md §2.1). The
 * signatures below are the contract that makes that possible, which is why they name no library
 * type in their <em>parameters</em> beyond {@link Packet} and {@link Session} — the two that
 * exist in every version.
 *
 * <p>This copy is the modern one: protocol 769 and later.
 */
final class PlayerSync {

    private PlayerSync() {}

    /**
     * The position carried by {@code packet}, or {@code null} if it is not a position packet.
     *
     * <p>Converted to plain numbers here, at the one point the protocol's own vector type is
     * touched. That is what keeps the rest of {@link BotSession} the same file for every version.
     */
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

    /** Moves to a position. The server validates the distance, so large jumps are rejected. */
    static void sendMove(Session session, double x, double y, double z) {
        session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player
                .ServerboundMovePlayerPosPacket(true, false, x, y, z));
    }
}
