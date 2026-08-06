package moe.vitamin.minecraft.mcp.bot.runner;

import moe.vitamin.minecraft.mcp.bot.core.BotIdentity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal MCP client, for asking the agent what it sees.
 *
 * <p>bot-core must not depend on the agent — nothing outside {@code agent-*} compiles against
 * it (CONTRIBUTING.md invariant 1). So the cross-layer test talks to the agent the way any other
 * client would: over HTTP, against the published tool contract.
 */
final class AgentProbe {

    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final String host;
    private final int port;
    private final String token;

    AgentProbe(String host, int port, String token) {
        this.host = host;
        this.port = port;
        this.token = token;
    }

    static AgentProbe fromSystemProperties() {
        return new AgentProbe(
                // The same host the bots dial. Hardcoding loopback here would have quietly
                // pointed the probe at a different machine than the one under test.
                System.getProperty("vitaminmcp.host", "127.0.0.1"),
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
                HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + "/mcp"))
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

    /** The address the server attributes a player's connection to. */
    String addressOf(String player) throws Exception {
        String response = call("state_query",
                "{\"kind\":\"player\",\"target\":\"%s\"}".formatted(player));
        return extract(response, "address");
    }

    String runCommand(String command) throws Exception {
        return call("command_exec", "{\"command\":\"%s\"}".formatted(command));
    }

    /**
     * Waits for an event, on the server rather than here.
     *
     * <p>This used to poll every 250ms from the test process. The agent now checks once per
     * tick, inside the server, and answers when the condition holds — one request instead of
     * dozens, and it cannot miss something that happened between two polls. On timeout the
     * response carries the events and log lines from that moment, so a failure arrives with
     * its own explanation.
     *
     * @param sinceSequence event sequence to count from, so an event that lands between the
     *                      action and this call still counts
     */
    String awaitEvent(String type, String player, long sinceSequence, Duration timeout)
            throws Exception {
        return call("wait_for", ("""
                {"condition":"event","eventType":"%s","player":"%s",                "sinceSequence":%d,"timeoutMillis":%d}""")
                .formatted(type, player, sinceSequence, timeout.toMillis()));
    }

    /** The event sequence as of now, to be passed to {@link #awaitEvent}. */
    long eventSequence() throws Exception {
        String response = call("server_info", "{}");
        String cursor = extract(response, "latestEventCursor");
        return cursor == null ? 0 : Long.parseLong(cursor.substring(cursor.indexOf(':') + 1));
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
