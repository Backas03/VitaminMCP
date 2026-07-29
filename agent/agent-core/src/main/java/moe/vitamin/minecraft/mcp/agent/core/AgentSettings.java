package moe.vitamin.minecraft.mcp.agent.core;

import java.util.List;
import java.util.Objects;

/**
 * Everything the agent's behaviour is configured by, as plain data.
 *
 * <p>Deliberately free of Bukkit types so the agent's logic can be unit tested without a
 * server (CLAUDE.md). Reading {@code config.yml} into this record is agent-mcp's job.
 *
 * @param bindAddress          interface to listen on
 * @param port                 port to listen on
 * @param authToken            shared secret required on every request; blank means the server
 *                             must refuse to start
 * @param readOnly             whether state-changing tools stay unexposed
 * @param eventBufferSize      retained events
 * @param logBufferSize        retained log entries
 * @param maxExceptionGroups   distinct exceptions retained
 * @param captureHighFrequency whether high-frequency events are captured at all
 * @param extraHighFrequency   additional simple type names to treat as high frequency
 * @param reinstatedTypes      simple type names to drop from the high-frequency list
 * @param scanPackages         packages searched for event classes
 * @param oauth                OAuth 2.1 settings for the HTTP endpoint
 * @param tls                  transport security, required once reachable remotely
 */
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
        TlsSettings tls) {

    /**
     * Loopback. Exposing the agent externally has to be a deliberate edit, never a default
     * (docs/design.md §14).
     */
    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";

    public static final int DEFAULT_PORT = 25585;

    public AgentSettings {
        Objects.requireNonNull(bindAddress, "bindAddress");
        extraHighFrequency = extraHighFrequency == null ? List.of() : List.copyOf(extraHighFrequency);
        reinstatedTypes = reinstatedTypes == null ? List.of() : List.copyOf(reinstatedTypes);
        scanPackages = scanPackages == null ? List.of() : List.copyOf(scanPackages);
        oauth = oauth == null ? OAuthSettings.disabled() : oauth;
        tls = tls == null ? TlsSettings.disabled() : tls;
    }

    /** Whether a usable token was configured. */
    public boolean hasAuthToken() {
        return authToken != null && !authToken.isBlank();
    }

    /**
     * Whether the agent is reachable from outside this machine.
     *
     * <p>Used to decide how loudly to warn: bound externally, a leaked token is a remote
     * console.
     */
    public boolean isExternallyReachable() {
        return !("127.0.0.1".equals(bindAddress) || "localhost".equals(bindAddress));
    }

    /**
     * Fails unless the settings are safe to start with.
     *
     * <p>A missing token is fatal rather than a warning. {@code command_exec} alone hands over
     * op, so an unauthenticated endpoint is a full console to whoever finds the port — and a
     * warning at startup is read by nobody. docs/design.md §14 is explicit that startup is
     * refused, and that this is not relaxed for convenience.
     *
     * @throws IllegalStateException if the agent must not start
     */
    public void validate() {
        oauth.validate();
        tls.validate();

        // The rule that makes remote installs safe by default. A bearer token granting
        // command_exec is a console password; sent over plaintext HTTP across a network it is
        // readable by anything on the path. Refusing to start is the only response that cannot
        // be ignored — a warning at startup is read by nobody, and this is software other
        // people install on servers we will never see (docs/design.md §14).
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
        // 0 is legitimate and means "any free port", which tests rely on.
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
