package moe.vitamin.minecraft.mcp.bot.spi;

import java.util.List;

/** The sidebar scoreboard. */
public record Scoreboard(String title, List<String> lines) {

    public Scoreboard {
        lines = List.copyOf(lines);
    }
}
