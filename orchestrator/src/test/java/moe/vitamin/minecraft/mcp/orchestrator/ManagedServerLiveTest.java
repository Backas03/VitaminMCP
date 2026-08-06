package moe.vitamin.minecraft.mcp.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Downloads a Paper build and starts it, agent and all. */
@EnabledIfSystemProperty(named = "vitaminmcp.liveServer", matches = "true")
class ManagedServerLiveTest {

    private static final String TOKEN = "orchestrator-live-test-token";

    @Test
    void downloadsAVersionStartsItAndTheAgentAnswers(@TempDir Path work) throws Exception {
        Path agentJar = Path.of(System.getProperty("vitaminmcp.agentJar", ""));
        assertTrue(Files.exists(agentJar),
                "pass -Dvitaminmcp.agentJar=<path to VitaminMCP.jar>");

        VersionMatrix matrix = VersionMatrix.load(Path.of("..", "versions.yaml"));
        VersionMatrix.Entry entry = matrix.versions().get(0);

        Path jar = new PaperDownloader(work.resolve("cache"))
                .fetch(entry.paperVersion(), entry.build());
        assertTrue(Files.size(jar) > 1_000_000, "the downloaded jar looks truncated");

        int port = 25599;
        int agentPort = 25598;

        try (ManagedServer server =
                     new ManagedServer(work.resolve("server"), jar, port, agentPort)) {
            server.prepare(null, agentJar, TOKEN);
            server.start(Path.of(System.getProperty("java.home")), Duration.ofMinutes(5));

            assertTrue(server.isRunning());

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + agentPort + "/mcp"))
                            .header("Authorization", "Bearer " + TOKEN)
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(20))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.body().contains("events_summary"), response.body());

            assertTrue(response.body().contains("command_exec"), response.body());
        }
    }

    @Test
    void aFreshServerDirectoryIsSelfContained(@TempDir Path work) throws Exception {
        Path agentJar = Path.of(System.getProperty("vitaminmcp.agentJar", ""));
        Path directory = work.resolve("server");

        new ManagedServer(directory, Path.of("unused.jar"), 25599, 25598)
                .prepare(null, agentJar, TOKEN);

        assertTrue(Files.readString(directory.resolve("eula.txt")).contains("eula=true"));
        assertTrue(Files.readString(directory.resolve("spigot.yml")).contains("bungeecord: true"));
        assertTrue(Files.readString(directory.resolve("server.properties")).contains("online-mode=false"));
        assertTrue(Files.exists(directory.resolve("plugins/VitaminMCP.jar")));

        String agentConfig = Files.readString(directory.resolve("plugins/VitaminMCP/config.yml"));
        assertTrue(agentConfig.contains(TOKEN));
        assertNotNull(agentConfig);
    }
}
