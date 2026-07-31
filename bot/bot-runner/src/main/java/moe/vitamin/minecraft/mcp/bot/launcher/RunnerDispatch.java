package moe.vitamin.minecraft.mcp.bot.launcher;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.util.List;
import moe.vitamin.minecraft.mcp.bot.core.RunnerProtocol;
import moe.vitamin.minecraft.mcp.bot.spi.BossBar;
import moe.vitamin.minecraft.mcp.bot.spi.BotBackend;
import moe.vitamin.minecraft.mcp.bot.spi.ClientView;
import moe.vitamin.minecraft.mcp.bot.spi.MenuItem;
import moe.vitamin.minecraft.mcp.bot.spi.OpenMenu;
import moe.vitamin.minecraft.mcp.bot.spi.Position;
import moe.vitamin.minecraft.mcp.bot.spi.Scoreboard;

/**
 * The line protocol, in the one place it is spoken.
 *
 * <p>This used to live inside the protocol module, which meant every backend compiled its own
 * copy of a switch statement that has nothing to do with any protocol — it parses strings and
 * calls methods. Here it is compiled once for every version there will ever be, and a backend
 * cannot drift from it (docs/multi-version.md §2.1.1).
 *
 * <p><b>Only protocol lines go to stdout.</b> Anything else corrupts the stream, so diagnostics
 * go to stderr, which the parent forwards to its own.
 */
final class RunnerDispatch {

    private final BotBackend backend;

    RunnerDispatch(BotBackend backend) {
        this.backend = backend;
    }

    /** Reads commands until the stream ends or {@code shutdown} arrives. */
    void run(BufferedReader in, PrintStream out) throws Exception {
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
                // The backend passes the server's own words through, so a rejected login reads
                // as what the server said rather than as a timeout in the parent.
                out.println(RunnerProtocol.encode(
                        RunnerProtocol.ERROR, command[0], String.valueOf(e.getMessage())));
            }
        }
        backend.shutdown();
    }

    private String handle(String[] command) throws Exception {
        String verb = command[0];
        return switch (verb) {
            case RunnerProtocol.SPAWN -> {
                // The client IP is passed through as given — empty means the real one.
                String clientIp = command.length > 2 ? command[2] : "";
                yield position(verb, backend.spawn(command[1], clientIp));
            }

            case RunnerProtocol.DESPAWN -> {
                backend.despawn(command[1]);
                yield ok(verb);
            }

            case RunnerProtocol.MOVE -> {
                backend.move(command[1],
                        Double.parseDouble(command[2]),
                        Double.parseDouble(command[3]),
                        Double.parseDouble(command[4]));
                yield ok(verb);
            }

            case RunnerProtocol.BREAK -> {
                backend.breakBlock(command[1],
                        Integer.parseInt(command[2]),
                        Integer.parseInt(command[3]),
                        Integer.parseInt(command[4]));
                yield ok(verb);
            }

            case RunnerProtocol.COMMAND -> {
                backend.command(command[1], command[2]);
                yield ok(verb);
            }

            case RunnerProtocol.CHAT -> {
                backend.chat(command[1], command[2]);
                yield ok(verb);
            }

            case RunnerProtocol.USE -> {
                backend.useBlock(command[1],
                        Integer.parseInt(command[2]),
                        Integer.parseInt(command[3]),
                        Integer.parseInt(command[4]),
                        command.length > 5 ? command[5] : "");
                yield ok(verb);
            }

            case RunnerProtocol.USE_ENTITY -> {
                double radius = command.length > 5 && !command[5].isBlank()
                        ? Double.parseDouble(command[5])
                        : 2.0;
                String type = command.length > 6 ? command[6] : null;
                int entityId = backend.useEntity(command[1],
                        Double.parseDouble(command[2]),
                        Double.parseDouble(command[3]),
                        Double.parseDouble(command[4]),
                        radius, type);
                yield RunnerProtocol.encode(RunnerProtocol.OK, verb, String.valueOf(entityId));
            }

            case RunnerProtocol.CLICK -> {
                backend.clickSlot(command[1],
                        Integer.parseInt(command[2]),
                        command.length > 3 ? command[3] : "left");
                yield ok(verb);
            }

            case RunnerProtocol.CLOSE_MENU -> {
                backend.closeMenu(command[1]);
                yield ok(verb);
            }

            case RunnerProtocol.MENU -> {
                OpenMenu menu = backend.menu(command[1]);
                yield RunnerProtocol.encode(RunnerProtocol.OK, verb,
                        String.valueOf(menu == null ? -1 : menu.containerId()),
                        // Sanitised, not merely stripped of tabs: a title is written by a plugin
                        // author and can hold any character this protocol gives meaning to.
                        menu == null ? "" : RunnerProtocol.sanitize(menu.title()));
            }

            case RunnerProtocol.INSPECT -> {
                ClientView view = backend.inspect(command[1]);
                OpenMenu menu = view.menu();
                Scoreboard scoreboard = view.scoreboard();
                yield RunnerProtocol.encode(RunnerProtocol.OK, verb,
                        String.valueOf(menu == null ? -1 : menu.containerId()),
                        menu == null ? "" : RunnerProtocol.sanitize(menu.title()),
                        items(view.items()),
                        records(view.messages()),
                        bossBars(view.bossBars()),
                        scoreboard == null ? "" : RunnerProtocol.sanitize(scoreboard.title()),
                        scoreboard == null ? "" : records(scoreboard.lines()));
            }

            case RunnerProtocol.POSITION -> position(verb, backend.position(command[1]));

            default -> RunnerProtocol.encode(
                    RunnerProtocol.ERROR, verb, "unknown command '" + verb + "'");
        };
    }

    private static String ok(String verb) {
        return RunnerProtocol.encode(RunnerProtocol.OK, verb);
    }

    private static String position(String verb, Position at) {
        return RunnerProtocol.encode(RunnerProtocol.OK, verb,
                String.valueOf(at.x()), String.valueOf(at.y()), String.valueOf(at.z()));
    }

    /** {@code slot ␟ itemId ␟ amount ␟ name ␟ customModelData ␟ lore}, joined by ␞. */
    private static String items(List<MenuItem> items) {
        StringBuilder out = new StringBuilder();
        for (MenuItem item : items) {
            if (out.length() > 0) {
                out.append(RunnerProtocol.RECORD_SEPARATOR);
            }
            out.append(item.slot()).append(RunnerProtocol.UNIT_SEPARATOR)
                    .append(item.itemId()).append(RunnerProtocol.UNIT_SEPARATOR)
                    .append(item.amount()).append(RunnerProtocol.UNIT_SEPARATOR)
                    .append(RunnerProtocol.sanitize(item.name())).append(RunnerProtocol.UNIT_SEPARATOR)
                    .append(RunnerProtocol.sanitize(item.customModelData()))
                    .append(RunnerProtocol.UNIT_SEPARATOR)
                    .append(RunnerProtocol.sanitize(item.lore()));
        }
        return out.toString();
    }

    /** {@code title ␟ progress ␟ colour}, one record each. */
    private static String bossBars(List<BossBar> bars) {
        StringBuilder out = new StringBuilder();
        for (BossBar bar : bars) {
            if (out.length() > 0) {
                out.append(RunnerProtocol.RECORD_SEPARATOR);
            }
            out.append(RunnerProtocol.sanitize(bar.title())).append(RunnerProtocol.UNIT_SEPARATOR)
                    .append(bar.progress()).append(RunnerProtocol.UNIT_SEPARATOR)
                    .append(RunnerProtocol.sanitize(bar.color()));
        }
        return out.toString();
    }

    private static String records(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) {
                out.append(RunnerProtocol.RECORD_SEPARATOR);
            }
            out.append(RunnerProtocol.sanitize(value));
        }
        return out.toString();
    }
}
