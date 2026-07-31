package moe.vitamin.minecraft.mcp.bot.runner;

import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;

/**
 * Right-clicking a block, which is how a container or a plugin menu gets opened.
 *
 * <p><b>A seam.</b> 1.21.2 added a "world border" flag to the use-item-on packet, so the
 * constructor takes one more argument than it used to. Nothing else about the call changed.
 *
 * <p>The cursor position on the face is sent as the middle of it. A real client sends where the
 * crosshair actually landed and a few plugins read it — placing a block on the upper or lower
 * half of a slab, for instance — but for opening things it is not consulted.
 *
 * <p>This copy is the modern one: protocol 768 and later.
 */
final class BlockUse {

    private BlockUse() {}

    /**
     * @param face     which side is being clicked. Matters for placement, not for opening
     * @param sequence the block-action counter. 1.19 added it so the client can undo a
     *                 prediction the server rejects; a bot that always sends 0 has its actions
     *                 treated as stale replays and silently dropped
     */
    static void useBlock(Session session, int x, int y, int z, Direction face, int sequence) {
        session.send(new ServerboundUseItemOnPacket(
                Vector3i.from(x, y, z),
                face == null ? Direction.UP : face,
                Hand.MAIN_HAND,
                0.5f, 0.5f, 0.5f,
                false,          // not inside a block
                false,          // world border not involved
                sequence));
    }
}
