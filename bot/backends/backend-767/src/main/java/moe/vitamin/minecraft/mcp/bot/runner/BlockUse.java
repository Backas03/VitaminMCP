package moe.vitamin.minecraft.mcp.bot.runner;

import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;

/** Right-clicking a block, on protocol 767. */
final class BlockUse {

    private BlockUse() {}

    static void useBlock(Session session, int x, int y, int z, Direction face, int sequence) {
        session.send(new ServerboundUseItemOnPacket(
                Vector3i.from(x, y, z),
                face == null ? Direction.UP : face,
                Hand.MAIN_HAND,
                0.5f, 0.5f, 0.5f,
                false,
                sequence));
    }
}
