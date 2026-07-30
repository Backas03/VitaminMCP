package moe.vitamin.minecraft.mcp.bot.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A bot runner process, and the bots inside it.
 *
 * <p>Replaces talking to MCProtocolLib directly. Callers no longer link against a protocol
 * library at all — they launch the runner built for the version under test and send it lines.
 * That is what lets one matrix run cover versions whose protocols differ, which a single JVM
 * cannot do because every MCProtocolLib build occupies the same package names.
 *
 * <p>The process is the isolation boundary. It is also the failure boundary: a runner that
 * wedges or dies takes its bots with it and nothing else, and killing it is the whole cleanup.
 */
public final class BotRunner implements AutoCloseable {

    /** How long a single command may take before the runner is presumed wedged. */
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);

    private final Process process;
    private final BufferedWriter toRunner;
    private final BufferedReader fromRunner;
    private final List<String> live = new ArrayList<>();

    private BotRunner(Process process) {
        this.process = process;
        this.toRunner = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.fromRunner = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    /**
     * Launches the runner jar and waits until it is ready.
     *
     * @param runnerJar the shaded runner for the protocol the server speaks
     * @param javaHome  JVM to run it with
     * @param host      server the bots will connect to
     * @param port      server's port
     */
    public static BotRunner launch(Path runnerJar, Path javaHome, String host, int port)
            throws IOException {
        Objects.requireNonNull(runnerJar, "runnerJar");

        Process process = new ProcessBuilder(
                javaHome.resolve("bin").resolve("java").toString(),
                "-jar", runnerJar.toAbsolutePath().toString(),
                host, String.valueOf(port))
                // Kept separate so a stack trace from the runner cannot be mistaken for a reply.
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();

        BotRunner runner = new BotRunner(process);
        String ready = runner.fromRunner.readLine();
        if (!RunnerProtocol.READY.equals(ready)) {
            runner.close();
            throw new IOException("The bot runner did not start. It said: " + ready);
        }
        return runner;
    }

    /** Connects a bot and waits until it is standing in the world. */
    public BotHandle spawn(String name) throws IOException {
        return spawn(name, null);
    }

    /**
     * Connects a bot that claims to be connecting from a particular address.
     *
     * <p>For testing what is keyed on the address rather than on the player: an IP ban, a
     * per-IP connection limit, a geo lookup. Anything else should use {@link #spawn(String)}
     * and let the bot report the address it really has — a fabricated one is a difference from
     * reality that every later step inherits.
     *
     * @param clientIp the address to claim, or {@code null} for the real one
     */
    public BotHandle spawn(String name, String clientIp) throws IOException {
        String[] reply = send(RunnerProtocol.SPAWN, name, clientIp == null ? "" : clientIp);
        live.add(name);
        return new BotHandle(this, name,
                Double.parseDouble(reply[2]), Double.parseDouble(reply[3]),
                Double.parseDouble(reply[4]));
    }

    public void despawn(String name) throws IOException {
        send(RunnerProtocol.DESPAWN, name);
        live.remove(name);
    }

    public boolean isRunning() {
        return process.isAlive();
    }

    /** Bots currently connected through this runner. */
    public List<String> bots() {
        return List.copyOf(live);
    }

    /**
     * Sends one command and returns its reply fields.
     *
     * <p>Synchronous, and synchronised, because the protocol is one line in and one line out —
     * two callers interleaving would each read the other's reply.
     *
     * @throws IOException if the runner reported an error, died, or stopped answering
     */
    synchronized String[] send(String... command) throws IOException {
        if (!process.isAlive()) {
            throw new IOException("The bot runner has exited");
        }
        toRunner.write(RunnerProtocol.encode(command));
        toRunner.newLine();
        toRunner.flush();

        String line = readWithTimeout();
        String[] reply = RunnerProtocol.decode(line);
        if (reply.length > 0 && RunnerProtocol.ERROR.equals(reply[0])) {
            // The runner passes the server's own words through, so a protocol mismatch reads as
            // "Outdated client! Please use 1.21.11" rather than as a timeout somewhere later.
            throw new IOException(reply.length > 2 ? reply[2] : "the runner reported an error");
        }
        return reply;
    }

    /**
     * Reads one reply, giving up rather than blocking forever.
     *
     * <p>A wedged runner would otherwise hang the matrix indefinitely, and a matrix that never
     * finishes is worse than one that reports a failure.
     */
    private String readWithTimeout() throws IOException {
        long deadline = System.nanoTime() + COMMAND_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (fromRunner.ready()) {
                String line = fromRunner.readLine();
                if (line == null) {
                    throw new IOException("The bot runner closed its output");
                }
                return line;
            }
            if (!process.isAlive()) {
                throw new IOException("The bot runner exited while a command was in flight");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for the bot runner", e);
            }
        }
        throw new IOException("The bot runner did not answer within " + COMMAND_TIMEOUT);
    }

    @Override
    public void close() {
        try {
            if (process.isAlive()) {
                toRunner.write(RunnerProtocol.encode(RunnerProtocol.SHUTDOWN));
                toRunner.newLine();
                toRunner.flush();
                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            process.destroyForcibly();
            live.clear();
        }
    }

    /** One bot, addressed by name through its runner. */
    public record BotHandle(BotRunner runner, String name, double x, double y, double z) {

        public int blockX() {
            return (int) Math.floor(x);
        }

        public int blockY() {
            return (int) Math.floor(y);
        }

        public int blockZ() {
            return (int) Math.floor(z);
        }

        public void moveTo(double x, double y, double z) throws IOException {
            runner.send(RunnerProtocol.MOVE, name,
                    String.valueOf(x), String.valueOf(y), String.valueOf(z));
        }

        public void breakBlock(int x, int y, int z) throws IOException {
            runner.send(RunnerProtocol.BREAK, name,
                    String.valueOf(x), String.valueOf(y), String.valueOf(z));
        }

        public void command(String command) throws IOException {
            runner.send(RunnerProtocol.COMMAND, name, command);
        }

        public void chat(String message) throws IOException {
            runner.send(RunnerProtocol.CHAT, name, message);
        }
        /** The bot's position now, which may differ from where it spawned. */
        public double[] position() throws IOException {
            String[] reply = runner.send(RunnerProtocol.POSITION, name);
            return new double[] {
                Double.parseDouble(reply[2]), Double.parseDouble(reply[3]),
                Double.parseDouble(reply[4])
            };
        }
    }
}
