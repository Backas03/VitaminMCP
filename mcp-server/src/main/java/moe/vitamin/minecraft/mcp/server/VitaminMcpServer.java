package moe.vitamin.minecraft.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * The MCP server a client such as Claude Code launches.
 *
 * <p>Speaks JSON-RPC over stdio: one message per line on stdin, one response per line on
 * stdout. That is how local MCP servers are started, and it means no port, no token and no
 * network — the client already trusts the process it spawned.
 *
 * <p>Which is the opposite of the agent's situation, and the reason the two do not share a
 * transport. The agent listens on a socket inside someone's Minecraft server and has to assume
 * hostile traffic; this runs as a child process of the thing talking to it.
 *
 * <p><b>Nothing is written to stdout except protocol messages.</b> A stray print corrupts the
 * stream and the client sees a parse error rather than whatever was printed, so diagnostics go
 * to stderr.
 */
public final class VitaminMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final SessionTools tools = new SessionTools();

    public static void main(String[] args) throws IOException {
        new VitaminMcpServer().run();
    }

    private void run() throws IOException {
        // Captured before anything can replace it, and stdout is deliberately not wrapped in
        // anything that might buffer a partial line.
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));

        System.err.println("VitaminMCP server ready on stdio");

        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            ObjectNode response = handle(line);
            if (response != null) {
                out.println(response);
            }
        }

        tools.close();
    }

    /** @return the response, or {@code null} for a notification */
    private ObjectNode handle(String line) {
        JsonNode request;
        try {
            request = MAPPER.readTree(line);
        } catch (IOException e) {
            return error(null, -32700, "Malformed JSON");
        }

        JsonNode id = request.has("id") ? request.get("id") : null;
        String method = request.path("method").asText("");
        JsonNode params = request.has("params") ? request.get("params") : MAPPER.createObjectNode();

        // Notifications get no reply, per JSON-RPC — including ones we do not recognise.
        if (id == null || id.isNull()) {
            return null;
        }

        try {
            return switch (method) {
                case "initialize" -> success(id, initialize(params));
                case "ping" -> success(id, MAPPER.createObjectNode());
                case "tools/list" -> {
                    ObjectNode result = MAPPER.createObjectNode();
                    result.set("tools", tools.listTools());
                    yield success(id, result);
                }
                case "tools/call" -> success(id, callTool(params));
                default -> error(id, -32601, "Unknown method: " + method);
            };
        } catch (RuntimeException e) {
            // Anything unhandled becomes a transport error rather than killing the loop; a
            // server that exits mid-session looks to the client like a crash with no message.
            System.err.println("Error handling " + method + ": " + e);
            return error(id, -32603, String.valueOf(e.getMessage()));
        }
    }

    private ObjectNode initialize(JsonNode params) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", params.path("protocolVersion").asText(PROTOCOL_VERSION));
        result.putObject("capabilities").putObject("tools");

        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "VitaminMCP");
        info.put("version", "1.0.0-SNAPSHOT");

        result.put("instructions",
                "Drives a Minecraft server for plugin testing: real bots connect over the "
                        + "protocol while an agent inside the server reports what happened. "
                        + "Call session_start first with the agent's token. Then either spawn "
                        + "bots and act step by step, or hand bot_run_scenario a whole scenario "
                        + "— it reports which step failed and what the server was doing at that "
                        + "moment. Prefer wait_for over waiting yourself; there is no sleep.");
        return result;
    }

    /**
     * Runs a tool.
     *
     * <p>Failures come back as content with {@code isError} rather than as a JSON-RPC error,
     * which is the MCP convention and the useful behaviour: a transport error is invisible to
     * the model, whereas "call session_start first" is something it can act on.
     */
    private ObjectNode callTool(JsonNode params) {
        String name = params.path("name").asText("");
        ObjectNode result = MAPPER.createObjectNode();

        try {
            JsonNode payload = tools.call(name, params.get("arguments"));
            result.putArray("content").addObject()
                    .put("type", "text")
                    .put("text", pretty(payload));
            result.put("isError", false);
        } catch (RuntimeException e) {
            result.putArray("content").addObject()
                    .put("type", "text")
                    .put("text", String.valueOf(e.getMessage()));
            result.put("isError", true);
        }
        return result;
    }

    private static String pretty(JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return String.valueOf(node);
        }
    }

    private static ObjectNode success(JsonNode id, JsonNode result) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? MAPPER.nullNode() : id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }
}
