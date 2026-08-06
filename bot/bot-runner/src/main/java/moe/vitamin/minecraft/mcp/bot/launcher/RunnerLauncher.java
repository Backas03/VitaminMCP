package moe.vitamin.minecraft.mcp.bot.launcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import moe.vitamin.minecraft.mcp.bot.core.RunnerProtocol;
import moe.vitamin.minecraft.mcp.bot.core.ping.ServerPing;
import moe.vitamin.minecraft.mcp.bot.spi.BotBackend;

/** The bot runner: one jar, every protocol. */
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

            out.println(RunnerProtocol.encode(RunnerProtocol.ERROR, "startup",
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
            e.printStackTrace(System.err);
            System.exit(1);
            return;
        }

        out.println(RunnerProtocol.encode(RunnerProtocol.READY, String.valueOf(protocol)));

        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        new RunnerDispatch(backend).run(in, out);
    }
}
