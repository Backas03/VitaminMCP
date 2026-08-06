package moe.vitamin.minecraft.mcp.agent.core;

import java.util.List;
import java.util.Objects;

/** Everything the agent's behaviour is configured by, as plain data. */
public record AgentSettings(
        String bindAddress,
        int port,
        String authToken,
        boolean readOnly,
        int eventBufferSize,
        int logBufferSize,
        int maxExceptionGroups,
        boolean captureHighFrequency,
        List<String> extraHighFrequency,
        List<String> reinstatedTypes,
        List<String> scanPackages,
        OAuthSettings oauth,
        TlsSettings tls,
        ActivityLogging activityLog) {

    /** Loopback. */
    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";

    public static final int DEFAULT_PORT = 25585;

    public AgentSettings {
        Objects.requireNonNull(bindAddress, "bindAddress");
        extraHighFrequency = extraHighFrequency == null ? List.of() : List.copyOf(extraHighFrequency);
        reinstatedTypes = reinstatedTypes == null ? List.of() : List.copyOf(reinstatedTypes);
        scanPackages = scanPackages == null ? List.of() : List.copyOf(scanPackages);
        oauth = oauth == null ? OAuthSettings.disabled() : oauth;
        tls = tls == null ? TlsSettings.disabled() : tls;
        activityLog = activityLog == null ? ActivityLogging.FULL : activityLog;
    }

    /** Whether a usable token was configured. */
    public boolean hasAuthToken() {
        return authToken != null && !authToken.isBlank();
    }

    /** Whether the agent is reachable from outside this machine. */
    public boolean isExternallyReachable() {
        return !("127.0.0.1".equals(bindAddress) || "localhost".equals(bindAddress));
    }

    /** Fails unless the settings are safe to start with. */
    public void validate() {
        oauth.validate();
        tls.validate();

        if (isExternallyReachable() && !tls.isProtected()) {
            throw new IllegalStateException(String.join(System.lineSeparator(),
                    "The agent is bound to " + bindAddress + ", which is reachable from other "
                            + "machines, but nothing is protecting the connection. The auth token "
                            + "would cross the network in clear text, and that token grants "
                            + "console access.",
                    "  Either set tls.enabled with a keystore so the agent serves HTTPS itself,",
                    "  or set tls.terminated-upstream if a proxy in front already terminates TLS,",
                    "  or leave bind-address at 127.0.0.1."));
        }
        if (!hasAuthToken()) {
            throw new IllegalStateException(
                    "No auth token is configured. The MCP endpoint grants access to server "
                            + "internals, so it will not start unauthenticated. Set 'auth-token' "
                            + "in config.yml to a generated secret, or set 'enabled: false' to "
                            + "turn the agent off.");
        }

        if (port < 0 || port > 65535) {
            throw new IllegalStateException("Port out of range: " + port);
        }
        if (eventBufferSize < 1 || logBufferSize < 1) {
            throw new IllegalStateException("Buffer sizes must be at least 1");
        }
    }

    /** The high-frequency policy these settings describe. */
    public HighFrequencyEvents highFrequencyEvents() {
        return HighFrequencyEvents.of(extraHighFrequency, reinstatedTypes);
    }
}
