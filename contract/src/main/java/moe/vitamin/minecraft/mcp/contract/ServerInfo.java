package moe.vitamin.minecraft.mcp.contract;

import java.util.List;
import java.util.Objects;

/** A snapshot of what the server is and what is running on it. */
public record ServerInfo(
        String implementation,
        String version,
        String minecraftVersion,
        List<Double> tps,
        int onlinePlayers,
        int maxPlayers,
        long uptimeMillis,
        List<PluginInfo> plugins) {

    public ServerInfo {
        Objects.requireNonNull(implementation, "implementation");
        Objects.requireNonNull(version, "version");
        tps = tps == null ? List.of() : List.copyOf(tps);
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
    }

    /** An installed plugin. */
    public record PluginInfo(String name, String version, boolean enabled) {
        public PluginInfo {
            Objects.requireNonNull(name, "name");
            version = version == null ? "" : version;
        }
    }
}
