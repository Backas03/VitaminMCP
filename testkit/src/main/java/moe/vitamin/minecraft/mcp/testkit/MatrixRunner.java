package moe.vitamin.minecraft.mcp.testkit;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import moe.vitamin.minecraft.mcp.bot.core.BotPool;
import moe.vitamin.minecraft.mcp.orchestrator.ManagedServer;
import moe.vitamin.minecraft.mcp.orchestrator.PaperDownloader;
import moe.vitamin.minecraft.mcp.orchestrator.VersionMatrix;

/**
 * Runs one scenario against every version in the matrix.
 *
 * <p>This is the payoff for everything below it: a scenario written once tells you not just
 * whether a plugin works, but on which versions. A failure on one version and not the others is
 * a completely different investigation from a failure everywhere, and running them by hand is
 * exactly the chore nobody does often enough to catch a regression early.
 *
 * <p>Each version gets its own directory, its own ports and its own freshly restored world, so
 * versions cannot contaminate each other — which is the same isolation problem as between runs
 * (docs/design.md §13), just more obvious when two servers exist at once.
 */
public final class MatrixRunner {

    /**
     * Servers started at once.
     *
     * <p>Two by default, not one per version. Each is a real JVM with a gigabyte or more of
     * heap, and a matrix that starves the machine measures the machine rather than the plugin —
     * which produces timing failures that look exactly like plugin bugs.
     */
    public static final int DEFAULT_PARALLELISM = 2;

    /** Ports are allocated in pairs from here, so versions never collide. */
    private static final int BASE_PORT = 25700;

    private final Path workDirectory;
    private final Path agentJar;
    private final Path worldTemplate;
    private final Path javaHome;
    private final int parallelism;

    /**
     * @param workDirectory where per-version server directories and the jar cache live
     * @param agentJar      the VitaminMCP plugin to install on each server
     * @param worldTemplate world copied in before each run, or {@code null} for a fresh world
     * @param javaHome      JVM used to run the servers
     */
    public MatrixRunner(Path workDirectory, Path agentJar, Path worldTemplate, Path javaHome) {
        this(workDirectory, agentJar, worldTemplate, javaHome, DEFAULT_PARALLELISM);
    }

    public MatrixRunner(
            Path workDirectory, Path agentJar, Path worldTemplate, Path javaHome, int parallelism) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be at least 1");
        }
        this.workDirectory = workDirectory;
        this.agentJar = agentJar;
        this.worldTemplate = worldTemplate;
        this.javaHome = javaHome;
        this.parallelism = parallelism;
    }

    /**
     * Runs {@code scenario} on every version.
     *
     * <p>Never throws for a version that fails or cannot start: a matrix exists to report on all
     * of them, and aborting at the first problem hides whether the rest would have passed —
     * which is the single most useful thing it could have told you.
     */
    public MatrixResult run(VersionMatrix matrix, String scenario, Duration startupTimeout) {
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, runnable -> {
            Thread thread = new Thread(runnable, "matrix");
            thread.setDaemon(true);
            return thread;
        });

        try {
            List<Future<MatrixResult.VersionOutcome>> pending = new ArrayList<>();
            int index = 0;
            for (VersionMatrix.Entry entry : matrix.versions()) {
                int port = BASE_PORT + index * 2;
                int agentPort = port + 1;
                index++;
                pending.add(pool.submit(runOne(entry, scenario, port, agentPort, startupTimeout)));
            }

            List<MatrixResult.VersionOutcome> outcomes = new ArrayList<>();
            for (int i = 0; i < pending.size(); i++) {
                String versionId = matrix.versions().get(i).id();
                try {
                    outcomes.add(pending.get(i).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    outcomes.add(MatrixResult.VersionOutcome.unavailable(versionId, "interrupted"));
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    outcomes.add(MatrixResult.VersionOutcome.unavailable(
                            versionId, String.valueOf(cause.getMessage())));
                }
            }
            return new MatrixResult(outcomes);
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<MatrixResult.VersionOutcome> runOne(
            VersionMatrix.Entry entry, String scenario, int port, int agentPort, Duration timeout) {

        return () -> {
            // A token per version, generated per run. A shared constant would work and would
            // also mean every managed server on the machine accepts the same secret.
            String token = generateToken();
            Path directory = workDirectory.resolve("servers").resolve(entry.id());

            Path jar = new PaperDownloader(workDirectory.resolve("cache"))
                    .fetch(entry.paperVersion(), entry.build());

            try (ManagedServer server = new ManagedServer(directory, jar, port, agentPort)) {
                server.prepare(worldTemplate, agentJar, token);
                server.start(javaHome, timeout);

                try (BotPool bots = new BotPool("127.0.0.1", port)) {
                    AgentClient agent = new AgentClient("127.0.0.1", agentPort, token);
                    ScenarioResult result = new ScenarioRunner(bots, agent).run(scenario);
                    return new MatrixResult.VersionOutcome(
                            entry.id(), result.passed(), result.describe(), result);
                }
            }
        };
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
