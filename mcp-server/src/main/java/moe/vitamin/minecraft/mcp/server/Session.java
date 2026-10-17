package moe.vitamin.minecraft.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import moe.vitamin.minecraft.mcp.bot.core.BotRunner;
import moe.vitamin.minecraft.mcp.testkit.AgentClient;
import moe.vitamin.minecraft.mcp.testkit.ScenarioRunner;

/** One connected server: its agent, and the bots on it when bots can join. */
final class Session {

    private final String host;
    private final int port;
    private final AgentClient agent;
    private final java.nio.file.Path runnerJar;
    private final Integer minecraftProtocol;

    /** What the agent said about its server when the session opened. */
    private final JsonNode serverInfo;

    /** Read from the agent's declared mode, closed if it does not say. */
    private final boolean readOnly;

    /**
     * Replaced by {@link #reset()}, which restarts the process rather than reusing it. Null while
     * the runner cannot start, and then {@link #botsUnavailable} says why.
     */
    private BotRunner bots;
    private String botsUnavailable;

    /** "(MC: 26.3)", which Paper and its forks append to {@code Bukkit.getVersion()}. */
    private static final Pattern MC_SUFFIX = Pattern.compile("\\(MC: ([0-9][^)\\s]*)\\)");

    /** The "1.21.8" of the API version "1.21.8-R0.1-SNAPSHOT", for a server without the suffix. */
    private static final Pattern LEADING_VERSION = Pattern.compile("[0-9]+\\.[0-9]+(\\.[0-9]+)?");

    Session(String host, int port, int mcpPort, String token, boolean tls, String tlsFingerprint,
            java.nio.file.Path runnerJar) {
        this(host, port, mcpPort, token, tls, tlsFingerprint, runnerJar, null);
    }

    /**
     * Asks the agent first, then starts the bots.
     *
     * <p>The agent is the part a session cannot do without: logs, events and state all come from
     * it. The runner can fail for reasons that leave the agent untouched — a server newer than the
     * runner's protocol support is the usual one — so that failure is recorded rather than thrown,
     * and the session opens without bots. An agent that does not answer still fails here.
     */
    Session(String host, int port, int mcpPort, String token, boolean tls, String tlsFingerprint,
            java.nio.file.Path runnerJar, Integer minecraftProtocol) {
        this.host = host;
        this.port = port;
        this.agent = new AgentClient(host, mcpPort, token, tls, tlsFingerprint);
        this.runnerJar = runnerJar;
        this.minecraftProtocol = minecraftProtocol;
        this.serverInfo = agent.call("server_info", AgentClient.arguments());
        this.readOnly = serverInfo.path("readOnly").asBoolean(true);
        launchBots();
    }

    AgentClient agent() {
        return agent;
    }

    JsonNode serverInfo() {
        return serverInfo;
    }

    /** The bot runner, or a refusal that says why there is none and what still works. */
    BotRunner bots() {
        if (bots == null) {
            throw new IllegalStateException("Bots are unavailable in this session: "
                    + botsUnavailable + " The agent tools (server_info, logs_query, events_query, "
                    + "exceptions_recent, state_query, wait_for, command_exec) still work.");
        }
        return bots;
    }

    boolean botsAvailable() {
        return bots != null;
    }

    /** Why the runner did not start, or null when it did. */
    String botsUnavailable() {
        return botsUnavailable;
    }

    boolean readOnly() {
        return readOnly;
    }

    /**
     * Whether the session is still usable. One without bots stays open for its agent; one with
     * bots ends when its runner exits.
     */
    boolean isRunning() {
        return bots == null || bots.isRunning();
    }

    ScenarioRunner runner() {
        return new ScenarioRunner(bots(), agent);
    }

    String describe() {
        if (bots == null) {
            return host + ":" + port + " (agent only: bots unavailable)";
        }
        return host + ":" + port + " (" + bots.bots().size() + " bots)";
    }

    /**
     * Disconnects every bot but keeps the session. Starts the runner afresh, so a session that
     * opened without bots gets another try; if it still cannot start, the session stays open for
     * the agent and the reason is thrown.
     */
    void reset() throws java.io.IOException {
        if (bots != null) {
            bots.close();
        }
        launchBots();
        if (bots == null) {
            throw new java.io.IOException(botsUnavailable);
        }
    }

    void close() {
        if (bots != null) {
            bots.close();
        }
    }

    /**
     * The Minecraft version the agent's server runs, or null when server_info does not say in a
     * form this recognises — and then the runner's ping decides alone, as it always did.
     */
    static String minecraftVersionOf(JsonNode serverInfo) {
        Matcher suffix = MC_SUFFIX.matcher(serverInfo.path("version").asText(""));
        if (suffix.find()) {
            return suffix.group(1);
        }
        Matcher leading = LEADING_VERSION.matcher(serverInfo.path("minecraftVersion").asText(""));
        return leading.lookingAt() ? leading.group() : null;
    }

    private void launchBots() {
        try {
            bots = BotRunner.launch(runnerJar, host, port, minecraftProtocol,
                    minecraftVersionOf(serverInfo));
            botsUnavailable = null;
        } catch (java.io.IOException e) {
            bots = null;
            botsUnavailable = e.getMessage();
        }
    }
}
