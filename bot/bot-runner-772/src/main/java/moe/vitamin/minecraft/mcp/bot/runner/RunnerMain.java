package moe.vitamin.minecraft.mcp.bot.runner;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import moe.vitamin.minecraft.mcp.bot.core.BotIdentity;
import moe.vitamin.minecraft.mcp.bot.core.RunnerProtocol;

/**
 * A bot runner for protocol 772 — Minecraft 1.21.7 and 1.21.8.
 *
 * <p>Runs as a child process so that a matrix can drive several protocol versions at once.
 * Every MCProtocolLib build uses the same package names, so two of them cannot share a
 * classpath; separate processes are the isolation, and they are also the cleanup.
 *
 * <p>Named for the protocol rather than a Minecraft version because one runner covers every
 * version sharing that protocol. Supporting a new protocol means a sibling module with its own
 * MCProtocolLib, not a change here.
 *
 * <p><b>Only protocol lines go to stdout.</b> Anything else corrupts the stream, so diagnostics
 * go to stderr, which the parent forwards to its own.
 */
public final class RunnerMain {

    /** Minecraft versions this runner speaks for. */
    public static final String PROTOCOL = "772";

    private final String host;
    private final int port;
    private final Map<String, BotSession> bots = new HashMap<>();

    private RunnerMain(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: bot-runner-" + PROTOCOL + " <host> <port>");
            System.exit(2);
        }
        new RunnerMain(args[0], Integer.parseInt(args[1])).run();
    }

    private void run() throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));

        out.println(RunnerProtocol.READY);

        String line;
        while ((line = in.readLine()) != null) {
            String[] command = RunnerProtocol.decode(line);
            if (command.length == 0) {
                continue;
            }
            if (RunnerProtocol.SHUTDOWN.equals(command[0])) {
                break;
            }
            try {
                out.println(handle(command));
            } catch (Exception e) {
                // The server's own words are passed through, so a rejected login reads as what
                // the server said rather than as a timeout in the parent.
                out.println(RunnerProtocol.encode(
                        RunnerProtocol.ERROR, command[0], String.valueOf(e.getMessage())));
            }
        }

        bots.values().forEach(BotSession::close);
    }

    private String handle(String[] command) throws Exception {
        String verb = command[0];
        return switch (verb) {
            case RunnerProtocol.SPAWN -> {
                String name = command[1];
                // Null for the claimed host, so the backend is told the host actually dialled;
                // hardcoding one made every server believe it had been reached as "localhost".
                // The client IP is passed through as given — empty means the real one.
                String clientIp = command.length > 2 ? command[2] : "";
                BotSession bot = BotSession
                        .open(host, port, BotIdentity.of(name), null, clientIp)
                        .connect(Duration.ofSeconds(30));
                bot.awaitGrounded(Duration.ofSeconds(15));
                bots.put(name, bot);
                yield position(RunnerProtocol.SPAWN, bot);
            }

            case RunnerProtocol.DESPAWN -> {
                BotSession bot = bots.remove(command[1]);
                if (bot != null) {
                    bot.close();
                }
                yield RunnerProtocol.encode(RunnerProtocol.OK, verb);
            }

            case RunnerProtocol.MOVE -> {
                require(command[1]).actions().moveTo(
                        Double.parseDouble(command[2]),
                        Double.parseDouble(command[3]),
                        Double.parseDouble(command[4]));
                yield RunnerProtocol.encode(RunnerProtocol.OK, verb);
            }

            case RunnerProtocol.BREAK -> {
                require(command[1]).actions().breakBlock(
                        Integer.parseInt(command[2]),
                        Integer.parseInt(command[3]),
                        Integer.parseInt(command[4]));
                yield RunnerProtocol.encode(RunnerProtocol.OK, verb);
            }

            case RunnerProtocol.COMMAND -> {
                require(command[1]).actions().command(command[2]);
                yield RunnerProtocol.encode(RunnerProtocol.OK, verb);
            }

            case RunnerProtocol.CHAT -> {
                require(command[1]).actions().chat(command[2]);
                yield RunnerProtocol.encode(RunnerProtocol.OK, verb);
            }

            case RunnerProtocol.USE -> {
                require(command[1]).actions().useBlock(
                        Integer.parseInt(command[2]),
                        Integer.parseInt(command[3]),
                        Integer.parseInt(command[4]),
                        command.length > 5 && !command[5].isBlank()
                                ? org.geysermc.mcprotocollib.protocol.data.game.entity.object
                                        .Direction.valueOf(command[5].toUpperCase(
                                                java.util.Locale.ROOT))
                                : null);
                yield RunnerProtocol.encode(RunnerProtocol.OK, verb);
            }

            case RunnerProtocol.USE_ENTITY -> {
                BotSession bot = require(command[1]);
                double x = Double.parseDouble(command[2]);
                double y = Double.parseDouble(command[3]);
                double z = Double.parseDouble(command[4]);
                double radius = command.length > 5 && !command[5].isBlank()
                        ? Double.parseDouble(command[5])
                        : 2.0;
                String type = command.length > 6 ? command[6] : null;

                int entityId = bot.entityNear(x, y, z, radius, type);
                if (entityId == BotSession.NO_ENTITY) {
                    // Saying what is actually nearby separates the three ways this fails: wrong
                    // coordinates, a radius too tight, and an entity the bot was never sent
                    // because it is outside its view distance.
                    String nearby = bot.describeEntitiesNear(x, y, z, radius);
                    yield RunnerProtocol.encode(RunnerProtocol.ERROR, verb,
                            "no " + (type == null || type.isBlank() ? "entity" : type)
                                    + " within " + radius + " blocks of " + x + " " + y + " " + z
                                    + (nearby.isEmpty()
                                            ? ". The bot has been told about no entities near "
                                                    + "there at all — check the coordinates, and "
                                                    + "that the bot is close enough to have them "
                                                    + "in view."
                                            : ". Nearby: " + nearby));
                }
                bot.actions().useEntity(entityId);
                yield RunnerProtocol.encode(RunnerProtocol.OK, verb, String.valueOf(entityId));
            }

            case RunnerProtocol.CLICK -> {
                require(command[1]).actions().clickSlot(
                        Integer.parseInt(command[2]),
                        command.length > 3 ? command[3] : "left");
                yield RunnerProtocol.encode(RunnerProtocol.OK, verb);
            }

            case RunnerProtocol.CLOSE_MENU -> {
                require(command[1]).actions().closeMenu();
                yield RunnerProtocol.encode(RunnerProtocol.OK, verb);
            }

            case RunnerProtocol.MENU -> {
                BotSession bot = require(command[1]);
                yield RunnerProtocol.encode(RunnerProtocol.OK, verb,
                        String.valueOf(bot.containerId()),
                        // Tab is the field separator, so a title containing one would shift
                        // every field after it into the wrong place.
                        bot.containerTitle() == null
                                ? "" : bot.containerTitle().replace('\t', ' '));
            }

            case RunnerProtocol.INSPECT -> {
                BotSession bot = require(command[1]);
                yield RunnerProtocol.encode(RunnerProtocol.OK, verb,
                        String.valueOf(bot.containerId()),
                        RunnerProtocol.sanitize(bot.containerTitle()),
                        bot.clientMenuItems(),
                        bot.receivedMessages());
            }

            case RunnerProtocol.POSITION -> position(RunnerProtocol.POSITION, require(command[1]));

            default -> RunnerProtocol.encode(
                    RunnerProtocol.ERROR, verb, "unknown command '" + verb + "'");
        };
    }

    private static String position(String verb, BotSession bot) {
        return RunnerProtocol.encode(RunnerProtocol.OK, verb,
                String.valueOf(bot.x()), String.valueOf(bot.y()), String.valueOf(bot.z()));
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
