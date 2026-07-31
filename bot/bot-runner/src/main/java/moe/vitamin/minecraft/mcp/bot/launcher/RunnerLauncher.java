package moe.vitamin.minecraft.mcp.bot.launcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import moe.vitamin.minecraft.mcp.bot.core.RunnerProtocol;
import moe.vitamin.minecraft.mcp.bot.core.ping.ServerPing;
import moe.vitamin.minecraft.mcp.bot.spi.BotBackend;

/**
 * The bot runner: one jar, every protocol.
 *
 * <p>Asks the server what it speaks, loads the backend built for that protocol out of this jar,
 * and then does nothing protocol-specific ever again — the line protocol above it and the packet
 * work below it are separated by {@link BotBackend} (docs/multi-version.md §2.1).
 *
 * <p>Runs as a child process of whatever is driving the test. That is the isolation for a wedged
 * or crashed protocol library, and it is also the cleanup: killing it takes the bots with it and
 * nothing else.
 *
 * <pre>
 *   java -jar bot-runner.jar &lt;host&gt; &lt;port&gt; [protocol]
 * </pre>
 *
 * <p>The third argument is a manual override for a server that cannot be pinged. It is not the
 * normal path: naming a protocol by hand is how the wrong one gets chosen.
 */
public final class RunnerLauncher {

    /** Long enough for a server that is still finishing its first tick, short enough to fail. */
    private static final int PING_TIMEOUT_MILLIS = 10_000;

    private RunnerLauncher() {}

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

        if (args.length < 2) {
            System.err.println("usage: bot-runner <host> <port> [protocol]");
            System.exit(2);
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);

        BotBackend backend;
        int protocol;
        try {
            protocol = args.length > 2
                    ? Integer.parseInt(args[2])
                    : ServerPing.protocol(host, port, PING_TIMEOUT_MILLIS);

            BackendCatalog catalog = new BackendCatalog();
            Path jar = catalog.extract(protocol);
            backend = BackendLoader.load(jar);

            if (backend.protocol() != protocol) {
                throw new IllegalStateException("The backend loaded for protocol " + protocol
                        + " reports that it speaks " + backend.protocol()
                        + ". The bundle is built wrong — " + jar.getFileName()
                        + " is not the jar its name claims.");
            }

            backend.start(host, port);
        } catch (Throwable e) {
            // Reported on stdout as a protocol line, not merely thrown. The parent is waiting to
            // read `ready`, and a process that dies silently reaches it as "it said: null" —
            // which describes the symptom and hides every one of these causes.
            //
            // Throwable, not Exception: loading a backend is exactly where an *Error* shows up.
            // A class the jar is missing or a method that moved between library versions arrives
            // as NoClassDefFoundError or NoSuchMethodError, and catching only Exception let the
            // most informative failure this launcher can have escape as silence.
            out.println(RunnerProtocol.encode(RunnerProtocol.ERROR, "startup",
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
            e.printStackTrace(System.err);
            System.exit(1);
            return;
        }

        // The protocol travels with `ready` so the parent can say which backend answered. A
        // mismatch between this and the server is caught above; this is for the log.
        out.println(RunnerProtocol.encode(RunnerProtocol.READY, String.valueOf(protocol)));

        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        new RunnerDispatch(backend).run(in, out);
    }
}
