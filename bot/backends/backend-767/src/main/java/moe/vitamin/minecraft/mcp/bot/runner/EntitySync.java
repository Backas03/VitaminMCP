package moe.vitamin.minecraft.mcp.bot.runner;

import org.geysermc.mcprotocollib.network.packet.Packet;

/**
 * Where the entities around the bot are, on protocol 767.
 *
 * <p>Overrides the shared copy. The spawn packet is in {@code clientbound.entity.spawn} here —
 * it moved up a package in 1.21.2 — and the entity teleport packet carries three doubles and an
 * {@code entityId} rather than a vector and an {@code id}.
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
        return new Teleport(
                teleport.getEntityId(), teleport.getX(), teleport.getY(), teleport.getZ());
    }
}
