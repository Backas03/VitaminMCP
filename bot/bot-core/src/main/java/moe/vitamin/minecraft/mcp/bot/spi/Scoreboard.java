package moe.vitamin.minecraft.mcp.bot.spi;

import java.util.List;

/**
 * The sidebar scoreboard.
 *
 * @param lines highest score first, which is the order the client draws them in
 */
public record Scoreboard(String title, List<String> lines) {

    public Scoreboard {
        lines = List.copyOf(lines);
    }
}
