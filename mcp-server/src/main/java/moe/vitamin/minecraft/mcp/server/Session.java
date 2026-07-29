package moe.vitamin.minecraft.mcp.server;

import moe.vitamin.minecraft.mcp.bot.core.BotPool;
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
    private final BotPool bots;

    Session(String host, int port, int mcpPort, String token, int maxBots) {
        this.host = host;
        this.port = port;
        this.agent = new AgentClient(host, mcpPort, token);
        this.bots = new BotPool(host, port, maxBots);
    }

    AgentClient agent() {
        return agent;
    }

    BotPool bots() {
        return bots;
    }

    ScenarioRunner runner() {
        return new ScenarioRunner(bots, agent);
    }

    String describe() {
        return host + ":" + port + " (" + bots.size() + " bots)";
    }

    /**
     * Disconnects every bot but keeps the session.
     *
     * <p>Does not reset the world, which is the other half of isolation (docs/design.md §13)
     * and needs the orchestrator to own the server's lifecycle — Stage 5. Between then and now,
     * a scenario that depends on world state has to establish it itself.
     */
    void reset() {
        bots.close();
    }

    void close() {
        bots.close();
    }
}
