package moe.vitamin.minecraft.mcp.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import moe.vitamin.minecraft.mcp.bot.core.BotRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioRunnerChatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path directory;

    @Test
    void sendingWithoutAChatEventFailsWithTheClientRefusal() throws Exception {
        ScenarioResult result = chat(false);
        assertFalse(result.passed());
        assertTrue(result.failure().detail().contains("AsyncChatEvent"));
        assertTrue(result.failure().evidence().contains("missing profile public key"));
        assertFalse(result.failure().evidence().contains("old greeting"));
    }

    @Test
    void aCapturedChatEventConfirmsDelivery() throws Exception {
        ScenarioResult result = chat(true);
        assertTrue(result.passed(), result.describe());
        assertEquals("server received player chat", result.steps().getFirst().detail());
    }

    private ScenarioResult chat(boolean matched) throws Exception {
        Path runner = directory.resolve("runner.mjs");
        Files.writeString(runner, """
                import { createInterface } from 'node:readline';
                console.log('ready\\t772');
                let sent = false;
                for await (const line of createInterface({input: process.stdin})) {
                  const [verb] = line.split('\\t');
                  if (verb === 'shutdown') break;
                  if (verb === 'spawn') console.log('ok\\tspawn\\t0\\t64\\t0');
                  else if (verb === 'chat') { sent = true; console.log('ok\\tchat'); }
                  else if (verb === 'inspect') {
                    const messages = ['0\\x1f1\\x1fold greeting'];
                    if (sent) messages.push('1\\x1f2\\x1fChat disabled due to missing profile public key');
                    console.log(['ok','inspect','-1','','',messages.join('\\x1e'),
                      '','','','','','','','','',sent ? '2' : '1','test-stream'].join('\\t'));
                  } else console.log('ok\\t' + verb);
                }
                """);

        AtomicReference<JsonNode> waitArguments = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody());
            String tool = request.path("params").path("name").asText();
            ObjectNode value = MAPPER.createObjectNode();
            if (tool.equals("server_info")) {
                value.put("latestEventCursor", "events:41");
                value.put("latestLogCursor", "logs:7");
            } else if (tool.equals("wait_for")) {
                waitArguments.set(request.path("params").path("arguments"));
                value.put("matched", matched);
            }
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.put("id", 1);
            response.putObject("result").putArray("content").addObject()
                    .put("type", "text").put("text", value.toString());
            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try (BotRunner bots = BotRunner.launch(runner, "127.0.0.1", 25565, 772)) {
            bots.spawn("Tester1");
            AgentClient agent = new AgentClient("127.0.0.1", server.getAddress().getPort(), "test");
            ScenarioResult result = new ScenarioRunner(bots, agent).run("""
                    [{"action":"chat","bot":"Tester1","message":"코인","timeoutMillis":100}]
                    """);
            JsonNode arguments = waitArguments.get();
            assertEquals("AsyncChatEvent", arguments.path("eventType").asText());
            assertEquals("Tester1", arguments.path("player").asText());
            assertEquals(41, arguments.path("sinceSequence").asLong());
            assertEquals(100, arguments.path("timeoutMillis").asLong());
            return result;
        } finally {
            server.stop(0);
        }
    }
}
