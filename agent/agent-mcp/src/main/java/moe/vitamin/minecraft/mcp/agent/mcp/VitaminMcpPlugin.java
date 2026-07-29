package moe.vitamin.minecraft.mcp.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import moe.vitamin.minecraft.mcp.agent.core.AgentSettings;
import moe.vitamin.minecraft.mcp.agent.core.CaptureService;
import moe.vitamin.minecraft.mcp.contract.ResponseBudget;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point: reads the configuration, starts capture, opens the MCP endpoint.
 *
 * <p>Deliberately thin. Everything that could be tested without a server lives in agent-core,
 * and everything protocol-shaped lives in {@link McpHttpServer}.
 */
public final class VitaminMcpPlugin extends JavaPlugin {

    private CaptureService capture;
    private McpHttpServer mcpServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();

        if (!config.getBoolean("enabled", true)) {
            getLogger().info("Disabled in config.yml; not starting.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        AgentSettings settings = readSettings(config);

        try {
            settings.validate();
        } catch (IllegalStateException e) {
            // Refusing to start is the specified behaviour, not a warning-and-continue. An
            // unauthenticated endpoint into server internals is worse than no endpoint, and a
            // startup warning is read by nobody (docs/design.md §14).
            getLogger().severe(e.getMessage());
            if (!settings.hasAuthToken()) {
                getLogger().severe("Suggested token (paste into config.yml): " + generateToken());
            }
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        capture = new CaptureService(this, settings);
        capture.start();

        ObjectMapper mapper = new ObjectMapper();
        AgentTools tools = new AgentTools(
                capture, mapper, ResponseBudget.DEFAULT, settings.readOnly());
        mcpServer = new McpHttpServer(
                settings, tools, mapper, getLogger(), getPluginMeta().getVersion());

        try {
            mcpServer.start();
        } catch (IOException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Could not open the MCP endpoint on "
                    + settings.bindAddress() + ":" + settings.port(), e);
            capture.stop();
            capture = null;
            mcpServer = null;
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (mcpServer != null) {
            mcpServer.stop();
            mcpServer = null;
        }
        if (capture != null) {
            capture.stop();
            capture = null;
        }
    }

    private AgentSettings readSettings(FileConfiguration config) {
        return new AgentSettings(
                config.getString("bind-address", AgentSettings.DEFAULT_BIND_ADDRESS),
                config.getInt("port", AgentSettings.DEFAULT_PORT),
                config.getString("auth-token", ""),
                config.getBoolean("read-only", true),
                config.getInt("event-buffer-size", 100_000),
                config.getInt("log-buffer-size", 20_000),
                config.getInt("max-exception-groups", 1_000),
                config.getBoolean("capture-high-frequency", false),
                stringList(config, "extra-high-frequency"),
                stringList(config, "reinstate-types"),
                stringList(config, "scan-packages"));
    }

    private static List<String> stringList(FileConfiguration config, String path) {
        return config.isList(path) ? List.copyOf(config.getStringList(path)) : List.of();
    }

    /** A token an operator can paste, so refusing to start still leaves an obvious next step. */
    private static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
