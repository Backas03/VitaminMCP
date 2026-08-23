package moe.vitamin.minecraft.mcp.contract;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a plugin declares about itself, and what it is actually configured to do.
 *
 * <p>The server holds all of this from the moment the plugin loads, and none of it was reachable.
 * Two dogfooding rounds ran into the same wall from opposite sides: one could not find out which
 * permission gates a command — {@code state_query}'s permission list can only be tested, never
 * enumerated, so the node had to be known already — and both had to fall back on the fixture
 * happening to log its own configuration at startup, which no real plugin does.
 *
 * <p>"Works for admins only" is the commonest bug report a server owner writes down. Answering it
 * means knowing the node, and the node is here.
 */
public record PluginDetail(
        String name,
        String version,
        boolean enabled,
        List<CommandDetail> commands,
        List<PermissionDetail> permissions,
        Map<String, String> config,
        boolean configTruncated) {

    public PluginDetail {
        Objects.requireNonNull(name, "name");
        commands = commands == null ? List.of() : List.copyOf(commands);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    /** A command from the plugin's own plugin.yml, and the node that gates it. */
    public record CommandDetail(
            String name, String description, String permission, List<String> aliases) {

        public CommandDetail {
            Objects.requireNonNull(name, "name");
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    /** A permission the plugin declares, and who has it before anyone edits anything. */
    public record PermissionDetail(String node, String defaultValue, String description) {

        public PermissionDetail {
            Objects.requireNonNull(node, "node");
        }
    }

    /**
     * Stands in for a config value that was not worth the risk of printing.
     *
     * <p>A plugin's config is where database passwords and API tokens live, and this tool is
     * readable by anything holding the agent's token. The key still appears, so a caller can see
     * that the setting exists and ask a human for it.
     */
    public static final String REDACTED = "(redacted)";
}
