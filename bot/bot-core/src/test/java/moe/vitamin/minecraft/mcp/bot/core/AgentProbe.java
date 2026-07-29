package moe.vitamin.minecraft.mcp.bot.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal MCP client, for asking the agent what it sees.
 *
 * <p>bot-core must not depend on the agent — nothing outside {@code agent-*} compiles against
 * it (CLAUDE.md invariant 1). So the cross-layer test talks to the agent the way any other
 * client would: over HTTP, against the published tool contract.
 */
final class AgentProbe {

    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final int port;
    private final String token;

    AgentProbe(int port, String token) {
        this.port = port;
        this.token = token;
    }

    static AgentProbe fromSystemProperties() {
        return new AgentProbe(
                Integer.getInteger("vitaminmcp.mcpPort", 25585),
                System.getProperty("vitaminmcp.token", ""));
    }

    /** Calls a tool and returns its text content, or the raw envelope if it failed. */
    String call(String tool, String argumentsJson) throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                  "name":"%s","arguments":%s}}
                """.formatted(tool, argumentsJson);

        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    /** The material at a block, as the server sees it. */
    String blockAt(String world, int x, int y, int z) throws Exception {
        String response = call("state_query",
                "{\"kind\":\"block\",\"world\":\"%s\",\"x\":%d,\"y\":%d,\"z\":%d}"
                        .formatted(world, x, y, z));
        return extract(response, "block");
    }

    /** A player's game mode, as the server sees it. */
    String gameModeOf(String player) throws Exception {
        String response = call("state_query",
                "{\"kind\":\"player\",\"target\":\"%s\"}".formatted(player));
        return extract(response, "gameMode");
    }

    String runCommand(String command) throws Exception {
        return call("command_exec", "{\"command\":\"%s\"}".formatted(command));
    }

    /**
     * Polls until an event of the given type appears, or gives up.
     *
     * <p>A bounded poll rather than a sleep: the bot's packet, the server tick, the MONITOR
     * listener and the buffer append all happen on different threads, so a query fired straight
     * after an action can legitimately arrive first.
     */
    String awaitEvent(String type, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String last = "";
        while (System.nanoTime() < deadline) {
            last = call("events_query", "{\"types\":[\"%s\"],\"limit\":50}".formatted(type));
            if (last.contains("\\\"type\\\" : \\\"" + type)) {
                return last;
            }
            Thread.sleep(250);
        }
        return last;
    }

    /**
     * Pulls a value out of the tool's JSON text content.
     *
     * <p>The payload arrives as a JSON string inside a JSON envelope, so its quotes are escaped
     * once. Crude, but bot-core has no JSON dependency and adding one to read three fields in a
     * test would be worse.
     */
    private static String extract(String response, String field) {
        String needle = "\\\"" + field + "\\\" : ";
        int at = response.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int start = response.indexOf("\\\"", at + needle.length());
        if (start < 0) {
            return null;
        }
        start += 2;
        int end = response.indexOf("\\\"", start);
        return end < 0 ? null : response.substring(start, end);
    }
}
