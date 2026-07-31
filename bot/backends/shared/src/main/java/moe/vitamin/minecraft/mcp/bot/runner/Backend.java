package moe.vitamin.minecraft.mcp.bot.runner;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import moe.vitamin.minecraft.mcp.bot.core.BotIdentity;
import moe.vitamin.minecraft.mcp.bot.spi.BotBackend;
import moe.vitamin.minecraft.mcp.bot.spi.ClientView;
import moe.vitamin.minecraft.mcp.bot.spi.OpenMenu;
import moe.vitamin.minecraft.mcp.bot.spi.Position;

/**
 * One protocol version's bots, behind the version-free {@link BotBackend}.
 *
 * <p>Loaded by the runner's launcher into a class loader of its own, which is what allows several
 * MCProtocolLib builds to live in one jar — every one of them occupies the same package names,
 * so they can share a process but never a class path (docs/multi-version.md §2.1).
 *
 * <p>Shared by every backend. A version that genuinely differs overrides the file that differs —
 * usually {@link PlayerSync} — rather than this one. Nothing here is protocol-specific: it is the
 * bookkeeping of which bots exist, and the translation between a name and a session.
 */
public final class Backend implements BotBackend {

    /**
     * Written by the build from the module's own name, so it cannot disagree with which jar this
     * is. A hand-written constant in shared source could not vary per backend at all, and one
     * per backend would be the first thing to go stale after a copy-paste.
     */
    private static final String DESCRIPTOR = "/META-INF/vitaminmcp-backend.properties";

    private final Map<String, BotSession> bots = new HashMap<>();

    private String host;
    private int port;

    @Override
    public int protocol() {
        Properties descriptor = new Properties();
        try (InputStream in = Backend.class.getResourceAsStream(DESCRIPTOR)) {
            if (in == null) {
                throw new IllegalStateException(
                        "This backend jar carries no " + DESCRIPTOR + ", so it cannot say which"
                                + " protocol it speaks. It was not built by the bot-backend"
                                + " convention plugin.");
            }
            descriptor.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + DESCRIPTOR, e);
        }
        return Integer.parseInt(descriptor.getProperty("protocol"));
    }

    @Override
    public void start(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public Position spawn(String name, String clientIp) throws Exception {
        // Null for the claimed host, so the backend is told the host actually dialled;
        // hardcoding one made every server believe it had been reached as "localhost".
        BotSession bot = BotSession
                .open(host, port, BotIdentity.of(name), null, clientIp)
                .connect(Duration.ofSeconds(30));
        bot.awaitGrounded(Duration.ofSeconds(15));
        bots.put(name, bot);
        return bot.position();
    }

    @Override
    public void despawn(String name) {
        BotSession bot = bots.remove(name);
        if (bot != null) {
            bot.close();
        }
    }

    @Override
    public void move(String name, double x, double y, double z) {
        require(name).actions().moveTo(x, y, z);
    }

    @Override
    public void breakBlock(String name, int x, int y, int z) {
        require(name).actions().breakBlock(x, y, z);
    }

    @Override
    public void command(String name, String command) {
        require(name).actions().command(command);
    }

    @Override
    public void chat(String name, String message) {
        require(name).actions().chat(message);
    }

    @Override
    public void useBlock(String name, int x, int y, int z, String face) {
        require(name).actions().useBlock(x, y, z,
                face == null || face.isBlank()
                        ? null
                        : org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction
                                .valueOf(face.toUpperCase(Locale.ROOT)));
    }

    @Override
    public int useEntity(String name, double x, double y, double z, double radius, String type) {
        BotSession bot = require(name);
        int entityId = bot.entityNear(x, y, z, radius, type);
        if (entityId == BotSession.NO_ENTITY) {
            // Saying what is actually nearby separates the three ways this fails: wrong
            // coordinates, a radius too tight, and an entity the bot was never sent because it
            // is outside its view distance.
            String nearby = bot.describeEntitiesNear(x, y, z, radius);
            throw new IllegalStateException(
                    "no " + (type == null || type.isBlank() ? "entity" : type)
                            + " within " + radius + " blocks of " + x + " " + y + " " + z
                            + (nearby.isEmpty()
                                    ? ". The bot has been told about no entities near there at"
                                            + " all — check the coordinates, and that the bot is"
                                            + " close enough to have them in view."
                                    : ". Nearby: " + nearby));
        }
        bot.actions().useEntity(entityId);
        return entityId;
    }

    @Override
    public void clickSlot(String name, int slot, String click) {
        require(name).actions().clickSlot(slot, click);
    }

    @Override
    public void closeMenu(String name) {
        require(name).actions().closeMenu();
    }

    @Override
    public OpenMenu menu(String name) {
        BotSession bot = require(name);
        return bot.hasMenuOpen() ? new OpenMenu(bot.containerId(), bot.containerTitle()) : null;
    }

    @Override
    public ClientView inspect(String name) {
        BotSession bot = require(name);
        return new ClientView(
                bot.hasMenuOpen() ? new OpenMenu(bot.containerId(), bot.containerTitle()) : null,
                bot.clientMenuItems(),
                bot.receivedMessages(),
                bot.clientBossBars(),
                bot.clientScoreboard());
    }

    @Override
    public Position position(String name) {
        return require(name).position();
    }

    @Override
    public void shutdown() {
        bots.values().forEach(BotSession::close);
        bots.clear();
    }

    private BotSession require(String name) {
        BotSession bot = bots.get(name);
        if (bot == null) {
            throw new IllegalStateException(
                    "no bot named " + name + " — spawn it before acting with it");
        }
        return bot;
    }
}
