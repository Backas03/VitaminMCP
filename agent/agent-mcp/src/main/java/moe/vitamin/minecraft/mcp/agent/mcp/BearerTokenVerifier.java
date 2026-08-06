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

/** Decides whether a bearer token may act on this server. */
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

    String refuse(String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return "a bearer token is required";
        }
        String presented = authorizationHeader.substring(7).trim();
        if (presented.isEmpty()) {
            return "a bearer token is required";
        }

        if (MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), staticToken)) {
            return null;
        }
        if (!oauth.enabled()) {
            return "invalid token";
        }
        return introspect(presented);
    }

    /** Asks the authorization server whether a token is currently good (RFC 7662). */
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

            return "the authorization server is unreachable";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "token validation was interrupted";
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Token introspection failed", e);
            return "the token could not be validated";
        }
    }

    /** Checks the token was minted for this server. */
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
