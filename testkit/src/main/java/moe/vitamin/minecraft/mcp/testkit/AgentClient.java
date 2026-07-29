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
 * it; the only thing joining the two sides is the tool contract (CLAUDE.md invariant 1). Going
 * through HTTP keeps that true, and has the side benefit that everything testkit relies on is
 * something a person could also do by hand.
 */
public final class AgentClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final URI endpoint;
    private final String token;

    public AgentClient(String host, int port, String token) {
        this(host, port, token, false);
    }

    /**
     * @param tls whether the agent is serving HTTPS. A remotely reachable agent refuses to start
     *            without transport security, so this is on for anything but a local server.
     */
    public AgentClient(String host, int port, String token, boolean tls) {
        this.endpoint = URI.create((tls ? "https://" : "http://") + host + ":" + port + "/mcp");
        this.token = Objects.requireNonNull(token, "token");
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

        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
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
        } catch (IOException e) {
            throw new AgentException("Could not reach the agent at " + endpoint
                    + ". Is the plugin installed and the token correct?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException("Interrupted calling " + tool, e);
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
            throw new AgentException(tool + " failed: " + body.get("error").path("message").asText());
        }

        JsonNode result = body.path("result");
        // Tool-level failures come back as content with isError rather than as a transport
        // error, which is the MCP convention — so they have to be unpacked, not assumed absent.
        if (result.path("isError").asBoolean()) {
            throw new AgentException(tool + ": " + textContent(result));
        }
        return parse(textContent(result));
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
