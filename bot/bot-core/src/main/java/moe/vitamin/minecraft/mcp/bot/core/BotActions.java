package moe.vitamin.minecraft.mcp.bot.core;

import java.time.Duration;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;

/**
 * What a bot can do once it is in the world.
 *
 * <p>Everything here sends a packet and returns. Nothing waits for a result, on purpose: what
 * counts as "the block broke" is a question about server state, and answering it by sleeping
 * after each action is how flaky tests are built. Confirmation belongs to the {@code wait_for}
 * machinery in Stage 3, driven by what the agent actually observed.
 */
public final class BotActions {

    /**
     * Long enough for soft blocks with bare hands in survival, and harmless in creative where
     * the block is already gone before it elapses.
     */
    public static final Duration DEFAULT_DIG_TIME = Duration.ofMillis(1500);

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
     * Breaks a block.
     *
     * <p>Sent as start-then-finish with no wait between. In creative, and for instantly
     * breakable blocks, the server completes it immediately; anything slower needs the dig
     * time to elapse, which is a Stage 3 concern rather than something to sleep through here.
     */
    public BotActions breakBlock(int x, int y, int z) {
        return breakBlock(x, y, z, DEFAULT_DIG_TIME);
    }

    /**
     * Breaks a block, allowing {@code digTime} for it.
     *
     * <p>The wait is the part that matters. In creative the server breaks the block on
     * START_DIGGING alone, so sending both immediately looks like it works — but in survival
     * the server measures how long the client spent digging and rejects a FINISH that arrives
     * too early. It rejects it silently: no event, no error, nothing in the log. A bot that
     * sends both back to back therefore appears to do nothing at all, which is exactly how
     * this first showed up.
     *
     * <p>Waiting here rather than in the caller keeps the failure mode out of every test that
     * breaks a block. It is a fixed wait, which Stage 3 will replace with a predicate on what
     * the server actually reports — the honest fix, once there is machinery for it.
     */
    public BotActions breakBlock(int x, int y, int z, Duration digTime) {
        require();
        Vector3i position = Vector3i.from(x, y, z);

        // Face UP: the top face of the block, which is the one reachable from standing on it.
        bot.session().send(new ServerboundPlayerActionPacket(
                PlayerAction.START_DIGGING, position, Direction.UP, ++sequence));

        try {
            Thread.sleep(digTime.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while breaking a block", e);
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

    /** How many block interactions have been sent, for assertions about sequencing. */
    public int sentSequence() {
        return sequence;
    }
}
