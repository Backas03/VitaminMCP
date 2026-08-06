package moe.vitamin.minecraft.mcp.bot.runner;

import moe.vitamin.minecraft.mcp.bot.core.BotIdentity;
import moe.vitamin.minecraft.mcp.bot.core.ForwardingHandshake;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.InteractAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;

/** What a bot can do once it is in the world. */
public final class BotActions {

    private final BotSession bot;

    /** Counts the block interactions this bot has sent. */
    private int sequence;

    public BotActions(BotSession bot) {
        this.bot = bot;
    }

    private void require() {
        if (!bot.isInGame()) {
            throw new IllegalStateException(
                    "Bot " + bot.identity().name() + " is not in the world"
                            + (bot.disconnectReason() == null ? "" : ": " + bot.disconnectReason()));
        }
    }

    /** Moves to a position. */
    public BotActions moveTo(double x, double y, double z) {
        require();

        PlayerSync.sendMove(bot.session(), x, y, z);
        return this;
    }

    /** Breaks a block and returns immediately. */
    public BotActions breakBlock(int x, int y, int z) {
        return breakBlock(x, y, z, Duration.ZERO);
    }

    /** Breaks a block, spending {@code digTime} on it first. */
    public BotActions breakBlock(int x, int y, int z, Duration digTime) {
        require();
        Vector3i position = Vector3i.from(x, y, z);

        bot.session().send(new ServerboundPlayerActionPacket(
                PlayerAction.START_DIGGING, position, Direction.UP, ++sequence));

        if (!digTime.isZero() && !digTime.isNegative()) {

            try {
                Thread.sleep(digTime.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while breaking a block", e);
            }
        }

        bot.session().send(new ServerboundPlayerActionPacket(
                PlayerAction.FINISH_DIGGING, position, Direction.UP, ++sequence));
        return this;
    }

    /** Runs a command as the bot. */
    public BotActions command(String command) {
        require();

        bot.session().send(new ServerboundChatCommandPacket(
                command.startsWith("/") ? command.substring(1) : command));
        return this;
    }

    /** Says something in chat. */
    public BotActions chat(String message) {
        return command("say " + message);
    }

    /** Right-clicks a block — which is how a container is opened. */
    public BotActions useBlock(int x, int y, int z, Direction face) {
        require();

        BlockUse.useBlock(bot.session(), x, y, z, face, ++sequence);
        return this;
    }

    /** Right-clicks an entity — an NPC, a villager, an armour stand. */
    public BotActions useEntity(int entityId) {
        require();
        bot.session().send(new ServerboundInteractPacket(
                entityId,
                InteractAction.INTERACT_AT,
                Hand.MAIN_HAND,
                false)
                .withTargetX(0.0f)
                .withTargetY(1.0f)
                .withTargetZ(0.0f));
        bot.session().send(new ServerboundInteractPacket(
                entityId,
                InteractAction.INTERACT,
                Hand.MAIN_HAND,
                false));
        return this;
    }

    /** Clicks a slot in the menu the server has open for this bot. */
    public BotActions clickSlot(int slot, String click) {
        require();
        int containerId = bot.containerId();
        if (containerId == BotSession.NO_CONTAINER) {
            throw new IllegalStateException(
                    "Bot " + bot.identity().name() + " has no menu open, so slot " + slot
                            + " cannot be clicked. Wait for inventory_open first — a menu does "
                            + "not open synchronously with the command that causes it.");
        }

        ContainerActionType type;
        ContainerAction action;
        switch (click == null ? "left" : click.toLowerCase(Locale.ROOT)) {
            case "left" -> {
                type = ContainerActionType.CLICK_ITEM;
                action = ClickItemAction.LEFT_CLICK;
            }
            case "right" -> {
                type = ContainerActionType.CLICK_ITEM;
                action = ClickItemAction.RIGHT_CLICK;
            }
            case "shift_left" -> {
                type = ContainerActionType.SHIFT_CLICK_ITEM;
                action = ShiftClickItemAction.LEFT_CLICK;
            }
            case "shift_right" -> {
                type = ContainerActionType.SHIFT_CLICK_ITEM;
                action = ShiftClickItemAction.RIGHT_CLICK;
            }
            default -> throw new IllegalArgumentException(
                    "Unknown click '" + click + "'. Use left, right, shift_left or shift_right.");
        }

        bot.session().send(new ServerboundContainerClickPacket(
                containerId,

                bot.containerStateId(),
                slot,
                type,
                action,
                null,
                Map.of()));
        return this;
    }

    /** Closes the open menu. */
    public BotActions closeMenu() {
        require();
        int containerId = bot.containerId();
        if (containerId != BotSession.NO_CONTAINER) {
            bot.session().send(new ServerboundContainerClosePacket(containerId));

            bot.forgetContainer();
        }
        return this;
    }

    /** How many block interactions have been sent, for assertions about sequencing. */
    public int sentSequence() {
        return sequence;
    }
}
