package moe.vitamin.minecraft.mcp.server;

import moe.vitamin.minecraft.mcp.bot.core.BotRunner;
import moe.vitamin.minecraft.mcp.testkit.AgentClient;
import moe.vitamin.minecraft.mcp.testkit.ScenarioRunner;

/**
 * One connected server, and the bots currently on it.
 *
 * <p>Held rather than rebuilt per call because bots are connections: a tool that reconnected
 * them each time would have no way to express "spawn two bots, then have them interact", which
 * is most of what this is for.
 */
final class Session {

    private final String host;
    private final int port;
    private final AgentClient agent;
    private final java.nio.file.Path runnerJar;
    private final java.nio.file.Path javaHome;

    /** Replaced by {@link #reset()}, which restarts the process rather than reusing it. */
    private BotRunner bots;

    Session(String host, int port, int mcpPort, String token, boolean tls, String tlsFingerprint,
            java.nio.file.Path runnerJar)
            throws java.io.IOException {
        this.host = host;
        this.port = port;
        this.agent = new AgentClient(host, mcpPort, token, tls, tlsFingerprint);
        this.runnerJar = runnerJar;
        this.javaHome = java.nio.file.Path.of(System.getProperty("java.home"));
        // Bots live in a child process built for the server's protocol version, so this JVM
        // never links a protocol library (docs/design.md §4.2).
        this.bots = BotRunner.launch(runnerJar, javaHome, host, port);
    }

    AgentClient agent() {
        return agent;
    }

    BotRunner bots() {
        return bots;
    }

    ScenarioRunner runner() {
        return new ScenarioRunner(bots, agent);
    }

    String describe() {
        return host + ":" + port + " (" + bots.bots().size() + " bots)";
    }

    /**
     * Disconnects every bot but keeps the session.
     *
     * <p>Every bot goes, and with it the runner process — the cleanest possible reset, since
     * nothing of the previous run's protocol state survives it. A fresh runner is then started,
     * because "keeps the session" has to mean the next {@code bot_spawn} works: killing the
     * process and leaving it dead made every later spawn fail with "the bot runner has exited",
     * and the tool that broke them reported success.
     *
     * <p>Does not reset the world, which is the other half of isolation (docs/design.md §13)
     * and needs the orchestrator to own the server's lifecycle — Stage 5. Between then and now,
     * a scenario that depends on world state has to establish it itself.
     */
    void reset() throws java.io.IOException {
        bots.close();
        bots = BotRunner.launch(runnerJar, javaHome, host, port);
    }

    void close() {
        bots.close();
    }
}
