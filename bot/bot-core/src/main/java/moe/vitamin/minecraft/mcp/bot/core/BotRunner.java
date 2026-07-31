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
import moe.vitamin.minecraft.mcp.bot.spi.BossBar;
import moe.vitamin.minecraft.mcp.bot.spi.ClientView;
import moe.vitamin.minecraft.mcp.bot.spi.MenuItem;
import moe.vitamin.minecraft.mcp.bot.spi.OpenMenu;
import moe.vitamin.minecraft.mcp.bot.spi.Scoreboard;

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
    private int protocol;

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
     * @param runnerJar the runner bundle. It picks its own backend by asking the server what it
     *                  speaks, so the same jar serves every supported version
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
        String[] fields = RunnerProtocol.decode(ready == null ? "" : ready);
        if (fields.length == 0 || !RunnerProtocol.READY.equals(fields[0])) {
            runner.close();
            // The runner reports a startup failure as `err startup <reason>`, so this carries
            // the reason — no backend for that protocol, an unreachable server — rather than
            // the fact that a line was not the one expected.
            throw new IOException("The bot runner did not start. It said: " + ready);
        }
        // Which backend answered. The runner chooses it by pinging the server, so a surprise
        // here is worth being able to see rather than inferring from a later packet error.
        runner.protocol = fields.length > 1 ? Integer.parseInt(fields[1]) : 0;
        return runner;
    }

    /** The protocol the loaded backend speaks, or 0 if the runner did not say. */
    public int protocol() {
        return protocol;
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

        /**
         * Right-clicks the nearest entity to a point — an NPC, a villager, an armour stand.
         *
         * <p>Named by position because the entity id the protocol uses is the server's own and is
         * never visible to whoever writes the scenario.
         *
         * @param radius how far from the point to look. Small on purpose: a generous radius
         *               silently picks a different NPC rather than failing
         * @param type   optional entity type filter, e.g. {@code PLAYER} for a Citizens NPC
         *               standing among mobs. Null or blank matches anything
         */
        public String useEntity(double x, double y, double z, double radius, String type)
                throws IOException {
            String[] reply = runner.send(RunnerProtocol.USE_ENTITY, name,
                    String.valueOf(x), String.valueOf(y), String.valueOf(z),
                    String.valueOf(radius), type == null ? "" : type);
            // The id it settled on. Coordinates name an entity, but two standing close together
            // are both plausible answers, so a scenario that clicked the wrong one otherwise has
            // to go and ask the server what was there.
            return reply.length > 2 ? reply[2] : "";
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

        /**
         * Right-clicks a block. This is how a container or a plugin menu gets opened.
         *
         * @param face side being clicked, or {@code null} for the top
         */
        public void useBlock(int x, int y, int z, String face) throws IOException {
            runner.send(RunnerProtocol.USE, name, String.valueOf(x), String.valueOf(y),
                    String.valueOf(z), face == null ? "" : face);
        }

        /**
         * Clicks a slot in the menu the bot has open.
         *
         * @param click {@code left}, {@code right}, {@code shift_left} or {@code shift_right}
         */
        public void clickSlot(int slot, String click) throws IOException {
            runner.send(RunnerProtocol.CLICK, name, String.valueOf(slot),
                    click == null || click.isBlank() ? "left" : click);
        }

        public void closeMenu() throws IOException {
            runner.send(RunnerProtocol.CLOSE_MENU, name);
        }

        /**
         * The menu the client has been told about, or {@code null} if none.
         *
         * <p>The client's view, not the server's — {@code state_query kind='inventory'} is the
         * server's. Comparing them is how "the plugin opened a menu but it never reached the
         * player" becomes visible instead of looking like an empty menu.
         */
        public OpenMenu menu() throws IOException {
            String[] reply = runner.send(RunnerProtocol.MENU, name);
            int containerId = Integer.parseInt(reply[2]);
            return containerId < 0 ? null : new OpenMenu(containerId, reply[3]);
        }

        /**
         * What the client was told, which the server cannot always be asked.
         *
         * <p>Two things live here for the same reason: neither survives on the server. A menu
         * drawn with packets leaves the Bukkit inventory empty, and a plugin's refusal is a
         * message to the player and nothing else.
         */
        public ClientView inspect() throws IOException {
            String[] reply = runner.send(RunnerProtocol.INSPECT, name);
            int containerId = Integer.parseInt(reply[2]);

            List<MenuItem> items = new ArrayList<>();
            for (String record : RunnerProtocol.records(reply[4])) {
                String[] parts = RunnerProtocol.fields(record);
                items.add(new MenuItem(
                        Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]), parts[3], parts[4], parts[5]));
            }
            List<BossBar> bossBars = new ArrayList<>();
            for (String record : RunnerProtocol.records(reply[6])) {
                String[] parts = RunnerProtocol.fields(record);
                bossBars.add(new BossBar(
                        parts[0], Float.parseFloat(parts[1]), parts[2]));
            }

            return new ClientView(
                    containerId < 0 ? null : new OpenMenu(containerId, reply[3]),
                    List.copyOf(items),
                    List.of(RunnerProtocol.records(reply[5])),
                    List.copyOf(bossBars),
                    reply[7].isEmpty() ? null : new Scoreboard(
                            reply[7], List.of(RunnerProtocol.records(reply[8]))));
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

    // OpenMenu, ClientView, BossBar, Scoreboard and MenuItem used to be nested here. They moved
    // to moe.vitamin.minecraft.mcp.bot.spi, where the backend that produces them can name them
    // too — the same records now describe the value on both sides of the runner process instead
    // of one side owning the type and the other reconstructing it.
}
