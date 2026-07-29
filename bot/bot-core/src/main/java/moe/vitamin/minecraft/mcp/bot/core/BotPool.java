package moe.vitamin.minecraft.mcp.bot.core;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bots currently connected to one server.
 *
 * <p>Exists mainly to put a ceiling on them. Each bot is a real connection with its own thread
 * and its own entity on the server, so a scenario that spawns them in a loop can degrade the
 * server it is supposed to be measuring — and a test that fails because the harness overloaded
 * the server is worse than no test, since it looks like a product bug. The cap turns that into
 * an immediate, obvious error instead.
 *
 * <p>The right ceiling is still an open question in docs/design.md; the default here is a
 * starting point, not a measured limit.
 */
public final class BotPool implements AutoCloseable {

    /** Deliberately conservative until someone measures what a server actually tolerates. */
    public static final int DEFAULT_MAX_BOTS = 10;

    private final String host;
    private final int port;
    private final int maxBots;
    private final Map<String, BotSession> bots = new ConcurrentHashMap<>();

    public BotPool(String host, int port) {
        this(host, port, DEFAULT_MAX_BOTS);
    }

    public BotPool(String host, int port, int maxBots) {
        if (maxBots < 1) {
            throw new IllegalArgumentException("maxBots must be at least 1 but was: " + maxBots);
        }
        this.host = host;
        this.port = port;
        this.maxBots = maxBots;
    }

    /**
     * Connects a bot and waits until it is in the world.
     *
     * @throws IllegalStateException if the pool is full, the name is taken, or the bot never
     *                               reached the world
     */
    public BotSession spawn(String name, Duration timeout) throws Exception {
        return spawn(BotIdentity.of(name), timeout);
    }

    public BotSession spawn(BotIdentity identity, Duration timeout) throws Exception {
        if (bots.size() >= maxBots) {
            throw new IllegalStateException(
                    "Bot limit reached (" + maxBots + "). Raise it deliberately rather than by "
                            + "accident: every bot is a real connection on the server under test.");
        }
        // Two bots under one name would share a UUID, and the server would kick the first as a
        // duplicate login — leaving a test wondering why an unrelated bot vanished.
        if (bots.containsKey(identity.name())) {
            throw new IllegalStateException("A bot named " + identity.name() + " is already connected");
        }

        BotSession bot = BotSession.open(host, port, identity, "localhost", "127.0.0.1")
                .connect(timeout);
        bots.put(identity.name(), bot);
        return bot;
    }

    /** Disconnects one bot, if it is present. */
    public void despawn(String name) {
        BotSession bot = bots.remove(name);
        if (bot != null) {
            bot.close();
        }
    }

    public BotSession get(String name) {
        return bots.get(name);
    }

    /** Every bot currently held, whether or not it is still connected. */
    public Collection<BotSession> bots() {
        return List.copyOf(bots.values());
    }

    public int size() {
        return bots.size();
    }

    public int maxBots() {
        return maxBots;
    }

    /** Disconnects every bot. Safe to call twice. */
    @Override
    public void close() {
        bots.values().forEach(BotSession::close);
        bots.clear();
    }
}
