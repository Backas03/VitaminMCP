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
import org.junit.jupiter.api.io.TempDir;

/**
 * The Stage 5 DoD: one scenario, every version in versions.yaml.
 *
 * <pre>
 *   ./gradlew :testkit:test --tests '*MatrixRunnerLiveTest*' \
 *     -Dvitaminmcp.liveServer=true -Dvitaminmcp.agentJar=...
 * </pre>
 *
 * <p>Starts real servers and downloads real builds, so it is gated and slow.
 */
@EnabledIfSystemProperty(named = "vitaminmcp.liveServer", matches = "true")
class MatrixRunnerLiveTest {

    /**
     * Deliberately version-agnostic.
     *
     * <p>Hard-coded coordinates or blocks would make a failure mean "this version generates a
     * different world", which is not what a matrix is for. Everything here is true of any
     * Paper server, so a failure is genuinely about the version.
     */
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

    private static MatrixRunner runner(Path work) {
        Path agentJar = Path.of(System.getProperty("vitaminmcp.agentJar", ""));
        assertTrue(Files.exists(agentJar),
                "pass -Dvitaminmcp.agentJar=<path to VitaminMCP.jar>");
        Path runnerJar = Path.of(System.getProperty("vitaminmcp.runnerJar", ""));
        assertTrue(Files.exists(runnerJar),
                "pass -Dvitaminmcp.runnerJar=<path to bot-runner-772.jar>");
        return new MatrixRunner(
                work, agentJar, runnerJar, null, Path.of(System.getProperty("java.home")));
    }

    @Test
    void oneScenarioRunsOnEveryVersion(@TempDir Path work) throws Exception {
        VersionMatrix matrix = VersionMatrix.load(Path.of("..", "versions.yaml"));

        MatrixResult result =
                runner(work).run(matrix, SCENARIO, Duration.ofMinutes(5));

        System.out.println(result.describe());

        assertEquals(matrix.versions().size(), result.results().size(),
                "every version must be reported on, including ones that could not start");
        assertTrue(result.allPassed(), result.describe());
    }

    @Test
    void aVersionThatCannotStartIsReportedRatherThanAborting(@TempDir Path work) throws Exception {
        // A matrix exists to report on all versions. Aborting at the first problem hides
        // whether the rest would have passed, which is the most useful thing it could say.
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
        // "could not be tested" reads differently from a scenario failure on purpose: the two
        // call for entirely different next steps.
        assertTrue(broken.summary().contains("could not be tested"), broken.summary());
    }
}
