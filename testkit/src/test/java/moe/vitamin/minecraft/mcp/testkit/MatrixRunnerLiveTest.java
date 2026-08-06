package moe.vitamin.minecraft.mcp.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import moe.vitamin.minecraft.mcp.orchestrator.VersionMatrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** The Stage 5 DoD: one scenario, every version in versions.yaml. */
@EnabledIfSystemProperty(named = "vitaminmcp.liveServer", matches = "true")
class MatrixRunnerLiveTest {

    /** Deliberately version-agnostic. */
    private static final String SCENARIO = """
            [
              {"action":"spawn","bot":"Tester1"},
              {"action":"assert_player","bot":"Tester1","online":true,"gameMode":"CREATIVE"},
              {"action":"console","command":"deop Tester1"},
              {"action":"assert_player","bot":"Tester1","op":false},
              {"action":"console","command":"op Tester1"},
              {"action":"assert_player","bot":"Tester1","op":true},
              {"action":"spawn","bot":"Tester2"},
              {"action":"assert_player","bot":"Tester2","online":true},
              {"action":"assert_event","eventType":"PlayerJoinEvent","player":"Tester2"}
            ]
            """;

    /** A working directory this test owns, cleaned up best-effort. */
    private Path work;

    @org.junit.jupiter.api.BeforeEach
    void createWorkDirectory() throws Exception {
        work = Files.createTempDirectory("vitaminmcp-matrix-");
    }

    @org.junit.jupiter.api.AfterEach
    void removeWorkDirectory() {
        if (work == null) {
            return;
        }
        try (var paths = Files.walk(work)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException stillInUse) {

                    System.err.println("left behind (still locked): " + path);
                }
            });
        } catch (java.io.IOException e) {
            System.err.println("could not walk " + work + ": " + e.getMessage());
        }
    }

    private static MatrixRunner runner(Path work) {
        Path agentJar = Path.of(System.getProperty("vitaminmcp.agentJar", ""));
        assertTrue(Files.exists(agentJar),
                "pass -Dvitaminmcp.agentJar=<path to VitaminMCP.jar>");
        Path runnerJar = Path.of(System.getProperty("vitaminmcp.runnerJar", ""));
        assertTrue(Files.exists(runnerJar),
                "pass -Dvitaminmcp.runnerJar=<path to bot-runner.jar>");
        return new MatrixRunner(
                work, agentJar, runnerJar, null, Path.of(System.getProperty("java.home")));
    }

    @Test
    void oneScenarioRunsOnEveryVersion() throws Exception {
        VersionMatrix matrix = VersionMatrix.load(Path.of("..", "versions.yaml"));

        MatrixResult result =
                runner(work).run(matrix, SCENARIO, Duration.ofMinutes(5));

        System.out.println(result.describe());

        assertEquals(matrix.versions().size(), result.results().size(),
                "every version must be reported on, including ones that could not start");
        assertTrue(result.allPassed(), result.describe());
    }

    @Test
    void aVersionThatCannotStartIsReportedRatherThanAborting() throws Exception {

        VersionMatrix matrix = VersionMatrix.parse("""
                versions:
                  - id: "1.21.8"
                    paper: { version: "1.21.8", build: 60 }
                  - id: "not-a-version"
                    paper: { version: "0.0.0" }
                """);

        MatrixResult result = runner(work).run(matrix, SCENARIO, Duration.ofMinutes(5));

        assertEquals(2, result.results().size());
        assertFalse(result.allPassed());
        assertTrue(result.results().get(0).passed(), result.describe());

        MatrixResult.VersionOutcome broken = result.results().get(1);
        assertFalse(broken.passed());

        assertTrue(broken.summary().contains("could not be tested"), broken.summary());
    }
}
