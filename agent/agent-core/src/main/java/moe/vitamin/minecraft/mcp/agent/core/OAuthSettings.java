package moe.vitamin.minecraft.mcp.agent.core;

import java.util.List;
import java.util.Objects;

/** OAuth 2.1 configuration for the agent's HTTP endpoint. */
public record OAuthSettings(
        boolean enabled,
        String issuer,
        String introspectionUrl,
        String clientId,
        String clientSecret,
        String resourceUrl,
        List<String> requiredScopes) {

    public OAuthSettings {
        issuer = orEmpty(issuer);
        introspectionUrl = orEmpty(introspectionUrl);
        clientId = orEmpty(clientId);
        clientSecret = orEmpty(clientSecret);
        resourceUrl = orEmpty(resourceUrl);
        requiredScopes = requiredScopes == null ? List.of() : List.copyOf(requiredScopes);
    }

    /** OAuth switched off — the agent falls back to its static token. */
    public static OAuthSettings disabled() {
        return new OAuthSettings(false, "", "", "", "", "", List.of());
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** Fails unless the settings can actually validate anything. */
    public void validate() {
        if (!enabled) {
            return;
        }
        if (issuer.isEmpty()) {
            throw new IllegalStateException("oauth.enabled is true but oauth.issuer is not set.");
        }
        if (introspectionUrl.isEmpty()) {
            throw new IllegalStateException(
                    "oauth.enabled is true but oauth.introspection-url is not set. The agent "
                            + "validates tokens by introspection; it cannot verify them itself.");
        }
        if (!introspectionUrl.startsWith("https://")
                && !introspectionUrl.startsWith("http://127.0.0.1")
                && !introspectionUrl.startsWith("http://localhost")) {

            throw new IllegalStateException(
                    "oauth.introspection-url must be https, or loopback for local testing.");
        }
        Objects.requireNonNull(requiredScopes, "requiredScopes");
    }
}
