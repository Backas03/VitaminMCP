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

/** A bot runner process, and the bots inside it. */
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

    /** Launches the runner jar and waits until it is ready. */
    public static BotRunner launch(Path runnerJar, Path javaHome, String host, int port)
            throws IOException {
        Objects.requireNonNull(runnerJar, "runnerJar");

        Process process = new ProcessBuilder(
                javaHome.resolve("bin").resolve("java").toString(),
                "-jar", runnerJar.toAbsolutePath().toString(),
                host, String.valueOf(port))

                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();

        BotRunner runner = new BotRunner(process);
        String ready = runner.fromRunner.readLine();
        String[] fields = RunnerProtocol.decode(ready == null ? "" : ready);
        if (fields.length == 0 || !RunnerProtocol.READY.equals(fields[0])) {
            runner.close();

            throw new IOException("The bot runner did not start. It said: " + ready);
        }

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

    /** Connects a bot that claims to be connecting from a particular address. */
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

    /** Sends one command and returns its reply fields. */
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

            throw new IOException(reply.length > 2 ? reply[2] : "the runner reported an error");
        }
        return reply;
    }

    /** Reads one reply, giving up rather than blocking forever. */
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

        /** Right-clicks the nearest entity to a point — an NPC, a villager, an armour stand. */
        public String useEntity(double x, double y, double z, double radius, String type)
                throws IOException {
            String[] reply = runner.send(RunnerProtocol.USE_ENTITY, name,
                    String.valueOf(x), String.valueOf(y), String.valueOf(z),
                    String.valueOf(radius), type == null ? "" : type);

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

        /** Right-clicks a block. */
        public void useBlock(int x, int y, int z, String face) throws IOException {
            runner.send(RunnerProtocol.USE, name, String.valueOf(x), String.valueOf(y),
                    String.valueOf(z), face == null ? "" : face);
        }

        /** Clicks a slot in the menu the bot has open. */
        public void clickSlot(int slot, String click) throws IOException {
            runner.send(RunnerProtocol.CLICK, name, String.valueOf(slot),
                    click == null || click.isBlank() ? "left" : click);
        }

        public void closeMenu() throws IOException {
            runner.send(RunnerProtocol.CLOSE_MENU, name);
        }

        /** The menu the client has been told about, or {@code null} if none. */
        public OpenMenu menu() throws IOException {
            String[] reply = runner.send(RunnerProtocol.MENU, name);
            int containerId = Integer.parseInt(reply[2]);
            return containerId < 0 ? null : new OpenMenu(containerId, reply[3]);
        }

        /** What the client was told, which the server cannot always be asked. */
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

}
