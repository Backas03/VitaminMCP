package moe.vitamin.minecraft.mcp.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import moe.vitamin.minecraft.mcp.agent.core.OAuthSettings;

/**
 * Decides whether a bearer token may act on this server.
 *
 * <p>Two ways in, and they are not equivalent. The static token is a shared secret: anyone
 * holding it is indistinguishable from anyone else holding it, it never expires, and revoking
 * it means editing a file and restarting. That is acceptable for a loopback endpoint on a
 * machine you already control, and it is why the agent shipped with it.
 *
 * <p>OAuth exists for everything else. A token issued by an authorization server carries an
 * identity, an audience and an expiry, can be revoked centrally, and — through the audience
 * check below — cannot be replayed against a different server by whoever it was issued for.
 */
final class BearerTokenVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final byte[] staticToken;
    private final OAuthSettings oauth;
    private final Logger logger;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    BearerTokenVerifier(String staticToken, OAuthSettings oauth, Logger logger) {
        this.staticToken = staticToken.getBytes(StandardCharsets.UTF_8);
        this.oauth = oauth;
        this.logger = logger;
    }

    /** @return why the token was refused, or {@code null} if it is good */
    String refuse(String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return "a bearer token is required";
        }
        String presented = authorizationHeader.substring(7).trim();
        if (presented.isEmpty()) {
            return "a bearer token is required";
        }

        // Compared with MessageDigest.isEqual because String.equals returns at the first
        // differing byte, leaking the secret a character at a time to anyone timing responses.
        if (MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), staticToken)) {
            return null;
        }
        if (!oauth.enabled()) {
            return "invalid token";
        }
        return introspect(presented);
    }

    /**
     * Asks the authorization server whether a token is currently good (RFC 7662).
     *
     * <p>Asked rather than decided locally, so a revoked token stops working immediately
     * instead of at its expiry.
     */
    private String introspect(String token) {
        try {
            String form = "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                    + "&token_type_hint=access_token";

            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(oauth.introspectionUrl()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(form));

            if (!oauth.clientId().isEmpty()) {
                String credentials = oauth.clientId() + ":" + oauth.clientSecret();
                request.header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
            }

            HttpResponse<String> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.warning("Token introspection returned HTTP " + response.statusCode());
                return "the token could not be validated";
            }

            JsonNode claims = MAPPER.readTree(response.body());
            if (!claims.path("active").asBoolean()) {
                return "the token is expired or revoked";
            }
            if (!oauth.issuer().isEmpty()
                    && claims.has("iss")
                    && !oauth.issuer().equals(claims.get("iss").asText())) {
                return "the token was issued by a different authorization server";
            }

            String audienceProblem = checkAudience(claims);
            if (audienceProblem != null) {
                return audienceProblem;
            }
            return checkScopes(claims);
        } catch (java.io.IOException e) {
            logger.log(Level.WARNING, "Could not reach the introspection endpoint", e);
            // Refused rather than allowed: an authorization server that cannot be reached is a
            // reason to stop, never a reason to wave requests through.
            return "the authorization server is unreachable";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "token validation was interrupted";
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Token introspection failed", e);
            return "the token could not be validated";
        }
    }

    /**
     * Checks the token was minted for this server.
     *
     * <p>Without it, a token a user granted to some other MCP server could be replayed here by
     * that server — the confused-deputy problem the MCP authorization spec calls out, and the
     * reason resource indicators exist.
     */
    private String checkAudience(JsonNode claims) {
        if (oauth.resourceUrl().isEmpty()) {
            return null;
        }
        JsonNode audience = claims.path("aud");
        boolean matches = audience.isArray()
                ? java.util.stream.StreamSupport.stream(audience.spliterator(), false)
                        .anyMatch(entry -> oauth.resourceUrl().equals(entry.asText()))
                : oauth.resourceUrl().equals(audience.asText());
        return matches ? null : "the token was not issued for this server";
    }

    private String checkScopes(JsonNode claims) {
        if (oauth.requiredScopes().isEmpty()) {
            return null;
        }
        java.util.Set<String> granted = java.util.Set.of(claims.path("scope").asText("").split(" "));
        for (String required : oauth.requiredScopes()) {
            if (!granted.contains(required)) {
                return "the token is missing the '" + required + "' scope";
            }
        }
        return null;
    }
}
