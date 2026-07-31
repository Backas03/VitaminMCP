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
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;

/**
 * What a bot can do once it is in the world.
 *
 * <p>Everything here sends a packet and returns. Nothing waits for a result, on purpose: what
 * counts as "the block broke" is a question about server state, and answering it by sleeping
 * after each action is how flaky tests are built. Confirmation belongs to the {@code wait_for}
 * machinery in Stage 3, driven by what the agent actually observed.
 */
public final class BotActions {

    private final BotSession bot;

    /**
     * Counts the block interactions this bot has sent.
     *
     * <p>1.19 added a sequence number to block actions so the client can undo a prediction the
     * server rejects. The server only checks that it advances, but a bot that always sends 0
     * has its actions treated as stale replays and silently dropped.
     */
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

    /** Moves to a position. The server validates the distance, so large jumps are rejected. */
    public BotActions moveTo(double x, double y, double z) {
        require();
        bot.session().send(new ServerboundMovePlayerPosPacket(true, false, x, y, z));
        return this;
    }

    /**
     * Breaks a block and returns immediately.
     *
     * <p>Does not wait, and deliberately does not confirm. Whether the block actually went is a
     * question about server state, and the answer belongs to {@code wait_for} on the agent —
     * asking for the outcome rather than assuming a duration is what keeps a scenario from
     * being calibrated to the machine it was written on (docs/roadmap.md Stage 3).
     *
     * <p>Correct as-is in creative, where the server breaks on START_DIGGING alone. In survival
     * the server measures how long the client spent digging, so use
     * {@link #breakBlock(int, int, int, Duration)} with the block's break time.
     */
    public BotActions breakBlock(int x, int y, int z) {
        return breakBlock(x, y, z, Duration.ZERO);
    }

    /**
     * Breaks a block, spending {@code digTime} on it first.
     *
     * <p>In survival the server measures how long the client spent digging and silently
     * discards a FINISH that arrives too early — no event, no error, nothing logged. The
     * duration is a property of the block and the tool, which a real client computes locally
     * because the protocol carries no "you may finish now" signal.
     *
     * <p>Pass {@link Duration#ZERO} in creative, where START_DIGGING alone breaks the block.
     */
    public BotActions breakBlock(int x, int y, int z, Duration digTime) {
        require();
        Vector3i position = Vector3i.from(x, y, z);

        // Face UP: the top face of the block, which is the one reachable from standing on it.
        bot.session().send(new ServerboundPlayerActionPacket(
                PlayerAction.START_DIGGING, position, Direction.UP, ++sequence));

        if (!digTime.isZero() && !digTime.isNegative()) {
            // Only when the caller knows the block needs it. This is a lower bound on how soon
            // FINISH may be sent, computed from the block, not a guess about whether the break
            // worked — that is still answered by waiting for the outcome.
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

    /**
     * Runs a command as the bot.
     *
     * <p>Sent without a signature. Commands only require one when the server enforces secure
     * chat, which a test backend running offline-mode does not.
     */
    public BotActions command(String command) {
        require();
        // The wire format carries the command without its slash; sending one produces a
        // "//command" the server cannot parse.
        bot.session().send(new ServerboundChatCommandPacket(
                command.startsWith("/") ? command.substring(1) : command));
        return this;
    }

    /**
     * Says something in chat.
     *
     * <p>Routed through {@code /say}-style command chat rather than the chat packet: since 1.19
     * a plain chat message carries a cryptographic signature over its content and the session's
     * message chain, and a bot with no signing key has its messages rejected or stripped. Going
     * through the command path avoids needing a key at all, and still produces the
     * {@code AsyncPlayerChatEvent} that tests assert on.
     */
    public BotActions chat(String message) {
        return command("say " + message);
    }

    /**
     * Right-clicks a block — which is how a container is opened.
     *
     * <p>The counterpart to {@link #breakBlock}, and the action that gets a menu on screen at
     * all: a chest, a villager, or a plugin's block listener all react to this, not to digging.
     *
     * <p>The cursor position on the face is sent as the middle of it. A real client sends where
     * the crosshair actually landed and a few plugins read it — placing a block on the upper or
     * lower half of a slab, for instance — but for opening things it is not consulted.
     *
     * @param face which side is being clicked, e.g. {@code UP}. Matters for placement, not for
     *             opening a container
     */
    public BotActions useBlock(int x, int y, int z, Direction face) {
        require();
        bot.session().send(new ServerboundUseItemOnPacket(
                Vector3i.from(x, y, z),
                face == null ? Direction.UP : face,
                Hand.MAIN_HAND,
                0.5f, 0.5f, 0.5f,
                false,          // not inside a block
                false,          // world border not involved
                ++sequence));
        return this;
    }

    /**
     * Right-clicks an entity — an NPC, a villager, an armour stand.
     *
     * <p>The counterpart to {@link #useBlock} for things that are not blocks. A Citizens NPC, a
     * shop villager and a plugin listening for {@code PlayerInteractEntityEvent} all react to
     * this and to nothing else: a bot standing next to an NPC and running its command is not the
     * same code path, and is exactly the path a right-click bug would hide behind.
     *
     * <p>Sends {@code INTERACT_AT} and then {@code INTERACT}, which is what a vanilla client
     * sends for one right click. This was originally {@code INTERACT} alone, on the reasoning
     * that Paper turns the pair into {@code PlayerInteractAtEntityEvent} and
     * {@code PlayerInteractEntityEvent} — the former extending the latter — so a plugin
     * listening only for the plain event would see one click as two.
     *
     * <p>That reasoning was wrong, and a live server said so. Sending only {@code INTERACT}
     * produced {@code PlayerInteractEntityEvent} and no menu, while a real player right-clicking
     * the same NPC produced both events and opened one. NPC plugins are commonly driven by the
     * {@code AT} variant or by a packet listener that expects it. And the double-fire being
     * avoided is not hypothetical harm: every real player already causes it, so a plugin that
     * cannot tolerate it is already broken for humans.
     *
     * <p>The target vector is the middle of a player-sized hitbox rather than where a crosshair
     * really landed. Plugins that read it are placing or aiming, not opening.
     *
     * @param entityId the server's numeric id, resolved from coordinates by the caller
     */
    public BotActions useEntity(int entityId) {
        require();
        bot.session().send(new ServerboundInteractPacket(
                entityId,
                InteractAction.INTERACT_AT,
                Hand.MAIN_HAND,
                false)              // not sneaking — sneak-click is a different interaction
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

    /**
     * Clicks a slot in the menu the server has open for this bot.
     *
     * <p>Does not wait, and does not check what happened — same reasoning as {@link
     * #breakBlock}. Whether the click did anything is a question about the plugin, answered by
     * waiting on what it changed.
     *
     * <p>The click carries no predicted slot changes and an empty cursor. A real client sends
     * what it thinks the result will be so the server can skip a correction when they agree;
     * predicting nothing simply means the server always corrects us, which costs one container
     * re-send and is always right. It is also the honest thing for a bot with no inventory
     * model: a wrong prediction would desynchronise silently, and menu plugins cancel the click
     * anyway, so there is usually nothing to predict.
     *
     * @param slot  slot index within the open view. Slots past the menu's own size address the
     *              player's inventory, exactly as they do for a real client
     * @param click one of {@code left}, {@code right}, {@code shift_left}, {@code shift_right}
     * @throws IllegalStateException if no menu is open
     */
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
                // The server's own counter, echoed back. A stale one is not rejected but makes
                // the server resend the container, which is a difference a test can trip over.
                bot.containerStateId(),
                slot,
                type,
                action,
                null,
                Map.of()));
        return this;
    }

    /**
     * Closes the open menu.
     *
     * <p>Worth doing between steps rather than leaving it to the next action: a plugin that
     * tracks who has its menu open will keep believing this bot does, and the next scenario
     * inherits that.
     */
    public BotActions closeMenu() {
        require();
        int containerId = bot.containerId();
        if (containerId != BotSession.NO_CONTAINER) {
            bot.session().send(new ServerboundContainerClosePacket(containerId));
        }
        return this;
    }

    /** How many block interactions have been sent, for assertions about sequencing. */
    public int sentSequence() {
        return sequence;
    }
}
