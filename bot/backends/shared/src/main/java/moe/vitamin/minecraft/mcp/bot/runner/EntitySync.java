package moe.vitamin.minecraft.mcp.bot.runner;

import org.geysermc.mcprotocollib.network.packet.Packet;

/** Where the entities around the bot are. */
final class EntitySync {

    private EntitySync() {}

    /** An entity appearing, as much of it as the tracker needs. */
    record Spawn(int id, String type, double x, double y, double z) {}

    /** An entity being put somewhere outright, as opposed to moved by a delta. */
    record Teleport(int id, double x, double y, double z) {}

    static Spawn spawnOf(Packet packet) {
        if (!(packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                .entity.ClientboundAddEntityPacket add)) {
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
