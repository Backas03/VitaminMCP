package moe.vitamin.minecraft.mcp.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * Talks to the agent over MCP.
 *
 * <p>testkit deliberately does not compile against the agent. The agent is a jar dropped into
 * whichever server is under test, and different Minecraft versions may need different builds of
 * it; the only thing joining the two sides is the tool contract (CONTRIBUTING.md invariant 1). Going
 * through HTTP keeps that true, and has the side benefit that everything testkit relies on is
 * something a person could also do by hand.
 */
public final class AgentClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final URI endpoint;
    private final String token;

    /** Whether a fingerprint was supplied, which decides what a TLS failure means. */
    private final boolean pinned;

    public AgentClient(String host, int port, String token) {
        this(host, port, token, false, null);
    }

    public AgentClient(String host, int port, String token, boolean tls) {
        this(host, port, token, tls, null);
    }

    /**
     * @param tls         whether the agent is serving HTTPS. A remotely reachable agent refuses
     *                    to start without transport security, so this is on for anything but a
     *                    local server
     * @param fingerprint SHA-256 of the certificate the agent must present, or {@code null} to
     *                    validate it the ordinary way against the system's trusted authorities
     */
    public AgentClient(String host, int port, String token, boolean tls, String fingerprint) {
        this.endpoint = URI.create((tls ? "https://" : "http://") + host + ":" + port + "/mcp");
        this.token = Objects.requireNonNull(token, "token");

        this.pinned = tls && fingerprint != null && !fingerprint.isBlank();

        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        if (pinned) {
            builder.sslContext(pinnedTo(fingerprint));
            // Hostname verification is switched off *because* the certificate is pinned, not to
            // weaken anything. A name match asks "was this issued for the host I typed"; a pin
            // asks "is this the exact certificate I was given", which is the stronger question
            // and the only one a self-signed certificate can answer. Leaving both on would mean
            // the operator also had to get a SAN right, for no gain.
            javax.net.ssl.SSLParameters parameters = new javax.net.ssl.SSLParameters();
            parameters.setEndpointIdentificationAlgorithm(null);
            builder.sslParameters(parameters);
        }
        this.http = builder.build();
    }

    /**
     * Trusts one certificate and nothing else.
     *
     * <p>This is what lets a self-signed agent be reached without installing anything on the
     * client: instead of teaching the machine to trust a new authority — which would apply to
     * every connection it ever makes — the fingerprint is checked against this one server.
     */
    private static javax.net.ssl.SSLContext pinnedTo(String fingerprint) {
        String expected = normalise(fingerprint);
        javax.net.ssl.X509TrustManager pinning = new javax.net.ssl.X509TrustManager() {

            @Override
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String type)
                    throws java.security.cert.CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new java.security.cert.CertificateException("the agent presented no certificate");
                }
                String actual = sha256(chain[0]);
                if (!actual.equals(expected)) {
                    throw new java.security.cert.CertificateException(
                            "the agent's certificate does not match the pinned fingerprint."
                                    + " Expected " + readable(expected) + " but it presented "
                                    + readable(actual) + ". If the agent's certificate was"
                                    + " regenerated, take the new fingerprint from its startup log.");
                }
            }

            @Override
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String type)
                    throws java.security.cert.CertificateException {
                throw new java.security.cert.CertificateException("this client does not act as a server");
            }

            @Override
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[0];
            }
        };

        try {
            javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLS");
            context.init(null, new javax.net.ssl.TrustManager[] {pinning}, null);
            return context;
        } catch (java.security.GeneralSecurityException e) {
            throw new AgentException("Could not set up certificate pinning", e);
        }
    }

    /**
     * Turns a TLS failure into the thing the reader has to do about it.
     *
     * <p>The two cases look identical from the outside and call for opposite actions: a pin that
     * no longer matches means the certificate changed, while no pin at all against a self-signed
     * certificate means one was never supplied. Java's own message — "unable to find valid
     * certification path to requested target" — says neither.
     */
    private String certificateProblem(javax.net.ssl.SSLException failure) {
        String detail = failure.getMessage() == null ? "" : failure.getMessage();
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof java.security.cert.CertificateException && cause.getMessage() != null) {
                detail = cause.getMessage();
                break;
            }
        }

        if (pinned) {
            return "The agent at " + endpoint + " did not present the pinned certificate. "
                    + detail;
        }
        return "The agent at " + endpoint + " presented a certificate that no trusted authority"
                + " signed, which is what a self-signed one looks like. Pass 'tlsFingerprint'"
                + " to pin it — the agent prints the value in its startup log. (" + detail + ")";
    }

    /** Lowercase hex, so {@code sha256:AA:BB}, {@code aabb} and {@code AA BB} all compare equal. */
    private static String normalise(String fingerprint) {
        return fingerprint.replaceAll("(?i)^sha-?256:", "")
                .replaceAll("[^0-9a-fA-F]", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static String sha256(java.security.cert.X509Certificate certificate)
            throws java.security.cert.CertificateException {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(certificate.getEncoded());
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new java.security.cert.CertificateException("SHA-256 is unavailable", e);
        }
    }

    /** Colon-separated, the form fingerprints are usually written and compared by eye. */
    private static String readable(String hex) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 2) {
            if (i > 0) {
                out.append(':');
            }
            out.append(hex, i, Math.min(i + 2, hex.length()));
        }
        return out.toString().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Calls a tool and returns its parsed result.
     *
     * @throws AgentException if the transport failed, or the tool reported an error
     */
    public JsonNode call(String tool, ObjectNode arguments) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", tool);
        params.set("arguments", arguments == null ? MAPPER.createObjectNode() : arguments);

        JsonNode result = rpc("tools/call", params, tool);

        // Tool-level failures come back as content with isError rather than as a transport
        // error, which is the MCP convention — so they have to be unpacked, not assumed absent.
        if (result.path("isError").asBoolean()) {
            throw new AgentException(tool + ": " + textContent(result));
        }
        return parse(textContent(result));
    }

    /**
     * The agent's own tool definitions, schemas and all.
     *
     * <p>Exists so that the parameters of a proxied tool can be published by whoever is proxying
     * it without restating them. Restating them would mean two descriptions of one tool, and the
     * copy is the one that goes stale — silently, because nothing compares them.
     */
    public JsonNode listTools() {
        return rpc("tools/list", MAPPER.createObjectNode(), "tools/list").path("tools");
    }

    /**
     * One JSON-RPC round trip.
     *
     * @param label what to name in an error message
     * @return the {@code result} object
     */
    private JsonNode rpc(String method, ObjectNode params, String label) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        request.set("params", params);

        // Generous, because wait_for legitimately blocks for as long as the caller asked it to.
        // A read timeout shorter than the agent's own cap would turn a working wait into a
        // transport error.
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (javax.net.ssl.SSLException e) {
            // Told apart from "could not connect" deliberately. The connection succeeded and the
            // certificate was refused, so pointing at the plugin or the token — which are both
            // fine — sends the reader looking in the wrong place entirely.
            throw new AgentException(certificateProblem(e), e);
        } catch (IOException e) {
            throw new AgentException("Could not reach the agent at " + endpoint
                    + ". Is the plugin installed and the token correct?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException("Interrupted calling " + label, e);
        }

        if (response.statusCode() == 401) {
            throw new AgentException("The agent rejected the token.");
        }
        if (response.statusCode() != 200) {
            throw new AgentException(
                    "The agent returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode body = parse(response.body());
        if (body.has("error")) {
            throw new AgentException(
                    label + " failed: " + body.get("error").path("message").asText());
        }
        return body.path("result");
    }

    private static String textContent(JsonNode result) {
        JsonNode content = result.path("content");
        return content.isArray() && !content.isEmpty()
                ? content.get(0).path("text").asText()
                : result.toString();
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new AgentException("The agent returned something that is not JSON: " + json, e);
        }
    }

    public static ObjectNode arguments() {
        return MAPPER.createObjectNode();
    }

    /** A failure talking to the agent, as opposed to a scenario step failing on its merits. */
    public static final class AgentException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AgentException(String message) {
            super(message);
        }

        AgentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
