package moe.vitamin.minecraft.mcp.bot.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal MCP client, for asking the agent what it saw.
 *
 * <p>bot-core must not depend on the agent — {@code mcp-server} never compiles against
 * {@code agent-*} and neither does this (CLAUDE.md invariant 1). So the cross-layer test talks
 * to the agent the way any other client would: over HTTP, against the published tool contract.
 * Doing it with raw strings keeps that boundary honest, at the cost of a slightly crude
 * assertion.
 */
final class AgentProbe {

    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private AgentProbe() {}

    /**
     * Calls {@code events_query} for one event type and returns the tool's text content.
     *
     * <p>Retries briefly: the bot's packet, the server's tick, the MONITOR listener and the
     * ring buffer append all happen on different threads, so a query fired immediately after
     * an action can legitimately arrive first. This is a bounded poll rather than a sleep,
     * which is the same distinction Stage 3's {@code wait_for} is built on.
     */
    static String eventsQuery(int port, String token, String type) throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                  "name":"events_query","arguments":{"types":["%s"],"limit":50}}}
                """.formatted(type);

        String last = "";
        for (int attempt = 0; attempt < 20; attempt++) {
            HttpResponse<String> response = CLIENT.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(10))
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            last = response.body();
            if (last.contains("\\\"type\\\" : \\\"" + type)) {
                return last;
            }
            Thread.sleep(250);
        }
        return last;
    }
}
