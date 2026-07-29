package moe.vitamin.minecraft.mcp.contract;

import java.util.List;
import java.util.Objects;

/**
 * What the server currently believes about one player.
 *
 * <p>The point of exposing this is that almost every confusing test result comes down to a
 * disagreement about it — the bot thinks it is somewhere the server does not, or acting with
 * permissions it does not have. Being able to ask settles those in one call instead of by
 * inference from events.
 *
 * @param name        player name
 * @param uuid        the UUID the server assigned, which is what permissions are keyed by
 * @param online      whether they are connected right now
 * @param gameMode    survival, creative, adventure or spectator
 * @param op          whether they hold operator status
 * @param world       world they are in, or {@code null} if offline
 * @param x           position, floored to the block
 * @param permissions permissions that were asked about, and whether they hold them
 */
public record PlayerState(
        String name,
        String uuid,
        boolean online,
        String gameMode,
        boolean op,
        String world,
        double x,
        double y,
        double z,
        List<PermissionCheck> permissions) {

    public PlayerState {
        Objects.requireNonNull(name, "name");
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }

    /**
     * One permission and whether the player has it.
     *
     * <p>Returned as an explicit pair rather than a list of granted nodes because a plugin's
     * permissions cannot be enumerated — only tested. Asking "do they have this" is the only
     * question the API can actually answer.
     */
    public record PermissionCheck(String node, boolean granted) {
        public PermissionCheck {
            Objects.requireNonNull(node, "node");
        }
    }
}
