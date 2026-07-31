package moe.vitamin.minecraft.mcp.bot.runner;

import org.geysermc.mcprotocollib.network.packet.Packet;

/**
 * Where the entities around the bot are.
 *
 * <p><b>A seam.</b> The spawn packet moved package in 1.21.2 — from
 * {@code clientbound.entity} to {@code clientbound.entity.spawn} — and the entity teleport
 * packet changed shape at the same time, from three doubles and an id to a position vector and a
 * differently named id. The relative-move packets did not change and stay in
 * {@link BotSession}.
 *
 * <p>This exists because the protocol addresses an entity by a numeric id the server invents,
 * which nothing outside the client ever sees. A scenario says "the NPC at these coordinates",
 * and only a tracker can turn that into the id an interact packet carries.
 *
 * <p>This copy is the modern one: protocol 768 and later.
 */
final class EntitySync {

    private EntitySync() {}

    /** An entity appearing, as much of it as the tracker needs. */
    record Spawn(int id, String type, double x, double y, double z) {}

    /** An entity being put somewhere outright, as opposed to moved by a delta. */
    record Teleport(int id, double x, double y, double z) {}

    /** @return the spawn {@code packet} announces, or {@code null} if it announces none */
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

    /** @return the teleport {@code packet} carries, or {@code null} if it carries none */
    static Teleport teleportOf(Packet packet) {
        if (!(packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                .entity.ClientboundTeleportEntityPacket teleport)) {
            return null;
        }
        var at = teleport.getPosition();
        return new Teleport(teleport.getId(), at.getX(), at.getY(), at.getZ());
    }
}
