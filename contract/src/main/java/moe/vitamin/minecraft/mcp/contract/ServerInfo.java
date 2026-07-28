package moe.vitamin.minecraft.mcp.contract;

import java.util.List;
import java.util.Objects;

/**
 * A snapshot of what the server is and what is running on it.
 *
 * <p>Usually the first thing worth asking for: knowing the implementation, the version and the
 * installed plugins decides how to read everything else the agent reports.
 *
 * @param implementation   server brand, e.g. {@code Paper}
 * @param version          full server version string
 * @param minecraftVersion the Minecraft version alone, e.g. {@code 1.20.4}
 * @param tps              load averages over 1/5/15 minutes; empty when the server does not
 *                         expose them
 * @param onlinePlayers    players currently connected
 * @param maxPlayers       configured player limit
 * @param uptimeMillis     how long the agent has been running
 * @param plugins          every plugin known to the plugin manager
 */
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

    /**
     * An installed plugin.
     *
     * <p>{@code enabled} matters more than it looks: a plugin that failed to enable is a
     * frequent root cause, and it is invisible if only the installed list is reported.
     *
     * @param name    plugin name as declared in its descriptor
     * @param version plugin version
     * @param enabled whether it is currently enabled
     */
    public record PluginInfo(String name, String version, boolean enabled) {
        public PluginInfo {
            Objects.requireNonNull(name, "name");
            version = version == null ? "" : version;
        }
    }
}
