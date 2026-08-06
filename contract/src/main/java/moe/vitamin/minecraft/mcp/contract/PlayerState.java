package moe.vitamin.minecraft.mcp.contract;

import java.util.List;
import java.util.Objects;

/** What the server currently believes about one player. */
public record PlayerState(
        String name,
        String uuid,
        boolean online,
        String address,
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

    /** One permission and whether the player has it. */
    public record PermissionCheck(String node, boolean granted) {
        public PermissionCheck {
            Objects.requireNonNull(node, "node");
        }
    }
}
