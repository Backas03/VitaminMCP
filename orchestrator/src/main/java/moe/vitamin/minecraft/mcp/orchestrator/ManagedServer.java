package moe.vitamin.minecraft.mcp.orchestrator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** A Paper server this process starts, watches and stops. */
public final class ManagedServer implements AutoCloseable {

    /** Marker Paper prints when it is accepting connections. */
    private static final String READY_MARKER = "Done (";

    private final Path directory;
    private final Path jar;
    private final int port;
    private final int agentPort;

    private Process process;

    public ManagedServer(Path directory, Path jar, int port, int agentPort) {
        this.directory = directory;
        this.jar = jar;
        this.port = port;
        this.agentPort = agentPort;
    }

    /** Lays out a server directory from scratch. */
    public void prepare(Path worldTemplate, Path agentJar, String agentToken) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("eula.txt"), "eula=true\n");

        Files.writeString(directory.resolve("server.properties"), """
                server-port=%d
                online-mode=false
                enforce-secure-profile=false
                max-players=20
                view-distance=6
                simulation-distance=6
                spawn-protection=0
                level-name=world
                gamemode=creative
                enable-command-block=false
                sync-chunk-writes=false
                """.formatted(port));

        Files.createDirectories(directory.resolve("config"));
        Files.writeString(directory.resolve("spigot.yml"), """
                settings:
                  bungeecord: true
                """);

        Path plugins = directory.resolve("plugins");
        Files.createDirectories(plugins.resolve("VitaminMCP"));
        Files.copy(agentJar, plugins.resolve("VitaminMCP.jar"), StandardCopyOption.REPLACE_EXISTING);

        Files.writeString(plugins.resolve("VitaminMCP").resolve("config.yml"), """
                enabled: true
                bind-address: "127.0.0.1"
                port: %d
                auth-token: "%s"
                read-only: false
                """.formatted(agentPort, agentToken));

        restoreWorld(worldTemplate);
    }

    /** Replaces the world with a copy of the template. */
    public void restoreWorld(Path worldTemplate) throws IOException {
        if (worldTemplate == null || !Files.isDirectory(worldTemplate)) {
            return;
        }
        Path world = directory.resolve("world");
        deleteRecursively(world);
        copyRecursively(worldTemplate, world);
    }

    /** Starts the server and waits until it is accepting connections. */
    public ManagedServer start(Path javaHome, Duration timeout) throws IOException, InterruptedException {
        Path log = directory.resolve("server.log");
        Files.deleteIfExists(log);

        List<String> command = new ArrayList<>(List.of(
                javaHome.resolve("bin").resolve("java").toString(),
                "-Xms1G", "-Xmx2G", "-jar", jar.toAbsolutePath().toString(), "--nogui"));

        process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();

        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("The server exited during startup. Log:\n" + tail(log, 30));
            }
            if (Files.exists(log)
                    && Files.readString(log, StandardCharsets.UTF_8).contains(READY_MARKER)) {
                return this;
            }
            Thread.sleep(250);
        }
        close();
        throw new IOException("The server did not start within " + timeout + ". Log:\n" + tail(log, 30));
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    public int port() {
        return port;
    }

    public int agentPort() {
        return agentPort;
    }

    public Path directory() {
        return directory;
    }

    /** Stops the server, politely first. */
    @Override
    public void close() {
        if (process == null) {
            return;
        }
        process.destroy();
        try {

            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        process = null;
    }

    private static String tail(Path log, int lines) {
        try {
            List<String> all = Files.readAllLines(log, StandardCharsets.UTF_8);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return "(no log)";
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void copyRecursively(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path path : paths.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
