package moe.vitamin.minecraft.mcp.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import moe.vitamin.minecraft.mcp.agent.core.ActivityLogging;
import moe.vitamin.minecraft.mcp.agent.core.AgentSettings;
import moe.vitamin.minecraft.mcp.agent.core.CaptureService;
import moe.vitamin.minecraft.mcp.agent.core.OAuthSettings;
import moe.vitamin.minecraft.mcp.agent.core.TlsSettings;
import moe.vitamin.minecraft.mcp.contract.ResponseBudget;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Plugin entry point: reads the configuration, starts capture, opens the MCP endpoint. */
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

            getLogger().severe(e.getMessage());
            if (!settings.hasAuthToken()) {
                getLogger().severe("Suggested token (paste into config.yml): " + generateToken());
            }
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        capture = new CaptureService(this, settings);
        capture.start();
        logCaptureState(settings);

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
            getLogger().info("MCP endpoint closed.");
        }
        if (capture != null) {
            Map<String, Object> status = capture.captureStatus();
            capture.stop();
            capture = null;
            getLogger().info("Capture stopped after " + status.get("eventsCaptured")
                    + " events and " + status.get("logsCaptured") + " log entries.");
        }
    }

    /** Says on the console what is being captured and what is not. */
    private void logCaptureState(AgentSettings settings) {
        Map<String, Object> status = capture.captureStatus();
        getLogger().info("Capturing " + status.get("eventTypesRegistered") + " event types into a "
                + settings.eventBufferSize() + "-record buffer; log capture "
                + (Boolean.TRUE.equals(status.get("logCaptureActive"))
                        ? "attached (" + settings.logBufferSize() + " records)"
                        : "UNAVAILABLE, so logs_query will stay empty") + ".");

        if (!settings.captureHighFrequency()) {
            getLogger().info("High-frequency events are not being captured "
                    + "(capture-high-frequency: false in config.yml).");
        }
        if (settings.activityLog() != ActivityLogging.FULL) {
            getLogger().info("Activity logging is set to '"
                    + settings.activityLog().name().toLowerCase(java.util.Locale.ROOT)
                    + "'; refused tokens and state-changing calls are still logged.");
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
                stringList(config, "scan-packages"),
                readOAuth(config),
                readTls(config),
                ActivityLogging.parse(config.getString("activity-log", "full")));
    }

    /** Reads the OAuth block. */
    private static OAuthSettings readOAuth(FileConfiguration config) {
        if (!config.getBoolean("oauth.enabled", false)) {
            return OAuthSettings.disabled();
        }
        return new OAuthSettings(
                true,
                config.getString("oauth.issuer", ""),
                config.getString("oauth.introspection-url", ""),
                config.getString("oauth.client-id", ""),
                config.getString("oauth.client-secret", ""),
                config.getString("oauth.resource-url", ""),
                stringList(config, "oauth.required-scopes"));
    }

    /** Reads the TLS block. */
    private static TlsSettings readTls(FileConfiguration config) {
        return new TlsSettings(
                config.getBoolean("tls.enabled", false),
                config.getString("tls.keystore", ""),
                config.getString("tls.keystore-password", ""),
                config.getBoolean("tls.terminated-upstream", false));
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
