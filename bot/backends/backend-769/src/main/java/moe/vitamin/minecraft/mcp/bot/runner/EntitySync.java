package moe.vitamin.minecraft.mcp.bot.runner;

import org.geysermc.mcprotocollib.network.packet.Packet;

/** Where the entities around the bot are, on protocol 769. */
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
