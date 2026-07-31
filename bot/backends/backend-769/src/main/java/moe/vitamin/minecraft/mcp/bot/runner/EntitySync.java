package moe.vitamin.minecraft.mcp.bot.runner;

import org.geysermc.mcprotocollib.network.packet.Packet;

/**
 * Where the entities around the bot are, on protocol 769.
 *
 * <p><b>The only file this backend overrides</b>, and for one import: the spawn packet is still
 * in {@code clientbound.entity.spawn} and moves up to {@code clientbound.entity} in 1.21.5.
 * Everything else about 1.21.4 — the session, the position packet, item components, the loading
 * handshake it introduced — already matches the shared source.
 *
 * <p>Identical to backend-768's copy, for the reason given in that backend's
 * {@code SessionFactory}.
 */
final class EntitySync {

    private EntitySync() {}

    record Spawn(int id, String type, double x, double y, double z) {}

    record Teleport(int id, double x, double y, double z) {}

    static Spawn spawnOf(Packet packet) {
        if (!(packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                .entity.spawn.ClientboundAddEntityPacket add)) {
            return null;
        }
        return new Spawn(
                add.getEntityId(),
                add.getType() == null ? "" : add.getType().toString(),
                add.getX(), add.getY(), add.getZ());
    }

    static Teleport teleportOf(Packet packet) {
        if (!(packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                .entity.ClientboundTeleportEntityPacket teleport)) {
            return null;
        }
        var at = teleport.getPosition();
        return new Teleport(teleport.getId(), at.getX(), at.getY(), at.getZ());
    }
}
