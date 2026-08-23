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

        // The shared cache rather than one under @TempDir. A server started from a jar keeps that
        // file open past the close that stopped it, and on Windows the temp-directory cleanup that
        // follows the test then fails on the still-locked jar — reproducibly, once a second test in
        // this class had already run. Caching outside the temp directory also downloads Paper once.
        Path jar = new PaperDownloader().fetch(entry.paperVersion(), entry.build());
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
        assertTrue(Files.readString(directory.resolve("server.properties")).contains("online-mode=false"));
        assertTrue(Files.exists(directory.resolve("plugins/VitaminMCP.jar")));

        String agentConfig = Files.readString(directory.resolve("plugins/VitaminMCP/config.yml"));
        assertTrue(agentConfig.contains(TOKEN));
        assertNotNull(agentConfig);
    }

    @Test
    void aRealWorldTemplateRestoresStateBeforeTheNextBoot(@TempDir Path work) throws Exception {
        Path agentJar = Path.of(System.getProperty("vitaminmcp.agentJar", ""));
        assertTrue(Files.exists(agentJar),
                "pass -Dvitaminmcp.agentJar=<path to VitaminMCP.jar>");

        VersionMatrix matrix = VersionMatrix.load(Path.of("..", "versions.yaml"));
        VersionMatrix.Entry entry = matrix.versions().get(0);
        Path paper = new PaperDownloader().fetch(entry.paperVersion(), entry.build());
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path template;

        // Generate a real Paper world first. Its directory becomes the fixture for the server
        // that is reset below; no hand-written level.dat can prove that the copy boots.
        try (ManagedServer seed =
                     new ManagedServer(work.resolve("seed"), paper, 25601, 25602)) {
            seed.prepare(null, agentJar, TOKEN + "-seed");
            seed.start(javaHome, Duration.ofMinutes(5));
            template = seed.directory().resolve("world");
            assertTrue(Files.isDirectory(template), "Paper did not create a world template");
        }

        Path restoredDirectory = work.resolve("restored");
        try (ManagedServer restored =
                     new ManagedServer(restoredDirectory, paper, 25603, 25604)) {
            restored.prepare(template, agentJar, TOKEN + "-restored");
            restored.start(javaHome, Duration.ofMinutes(5));

            call(25604, TOKEN + "-restored", "command_exec",
                    "{\"command\":\"forceload add 0 0\"}");
            call(25604, TOKEN + "-restored", "command_exec",
                    "{\"command\":\"setblock 0 64 0 minecraft:diamond_block\"}");
            assertTrue(call(25604, TOKEN + "-restored", "state_query",
                    "{\"kind\":\"block\",\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0}")
                            .contains("DIAMOND_BLOCK"));
        }

        try (ManagedServer restored =
                     new ManagedServer(restoredDirectory, paper, 25603, 25604)) {
            restored.restoreWorld(template);
            restored.start(javaHome, Duration.ofMinutes(5));

            call(25604, TOKEN + "-restored", "command_exec",
                    "{\"command\":\"forceload add 0 0\"}");
            String afterRestore = call(25604, TOKEN + "-restored", "state_query",
                    "{\"kind\":\"block\",\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0}");
            assertTrue(!afterRestore.contains("DIAMOND_BLOCK"),
                    "the template restore left the changed block behind: " + afterRestore);
        }
    }

    private static String call(int agentPort, String token, String tool, String arguments)
            throws Exception {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":"
                + arguments + "}}";
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + agentPort + "/mcp"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(20))
                        .POST(HttpRequest.BodyPublishers.ofString(request))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }
}
