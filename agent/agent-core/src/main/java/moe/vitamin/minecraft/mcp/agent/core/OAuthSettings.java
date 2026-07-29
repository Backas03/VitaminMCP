package moe.vitamin.minecraft.mcp.agent.core;

import java.util.List;
import java.util.Objects;

/**
 * OAuth 2.1 configuration for the agent's HTTP endpoint.
 *
 * <p>An MCP server is a <em>resource server</em>, never an authorization server. It does not
 * issue tokens, hold user credentials or run a login flow — it accepts a token someone else
 * issued and decides whether to honour it. Building the other half here would mean putting an
 * identity provider inside a Minecraft plugin, which is not a thing anyone should deploy.
 *
 * <p>Validation is by introspection (RFC 7662) rather than by verifying a JWT signature
 * locally. That keeps the agent free of a JOSE library and a JWKS cache — dependencies that
 * would have to be relocated and would each be another thing colliding inside a server JVM
 * (docs/design.md §7). The cost is a call to the authorization server per request, which is
 * acceptable for a tool a person drives.
 *
 * @param enabled          whether bearer tokens are validated against an authorization server
 * @param issuer           the authorization server, advertised in resource metadata
 * @param introspectionUrl RFC 7662 endpoint used to validate a token
 * @param clientId         this resource server's own credentials at the introspection endpoint
 * @param clientSecret     paired with {@code clientId}
 * @param resourceUrl      this server's canonical URL, which tokens must be audienced to
 * @param requiredScopes   scopes a token must carry
 */
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

    /**
     * Fails unless the settings can actually validate anything.
     *
     * <p>Half-configured OAuth is worse than none: it looks like authentication is in force
     * while every request falls through to whatever the fallback is.
     */
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
            // A token sent in clear to a remote endpoint is a token given away.
            throw new IllegalStateException(
                    "oauth.introspection-url must be https, or loopback for local testing.");
        }
        Objects.requireNonNull(requiredScopes, "requiredScopes");
    }
}
