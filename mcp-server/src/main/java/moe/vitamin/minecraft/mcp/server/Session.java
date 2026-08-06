package moe.vitamin.minecraft.mcp.server;

import moe.vitamin.minecraft.mcp.bot.core.BotRunner;
import moe.vitamin.minecraft.mcp.testkit.AgentClient;
import moe.vitamin.minecraft.mcp.testkit.ScenarioRunner;

/** One connected server, and the bots currently on it. */
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

    /** Disconnects every bot but keeps the session. */
    void reset() throws java.io.IOException {
        bots.close();
        bots = BotRunner.launch(runnerJar, javaHome, host, port);
    }

    void close() {
        bots.close();
    }
}
