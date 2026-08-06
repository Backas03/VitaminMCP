package moe.vitamin.minecraft.mcp.bot.spi;

import java.util.List;

/** Everything the client knows that the server will not report. */
public record ClientView(
        OpenMenu menu,
        List<MenuItem> items,
        List<String> messages,
        List<BossBar> bossBars,
        Scoreboard scoreboard) {

    public ClientView {
        items = List.copyOf(items);
        messages = List.copyOf(messages);
        bossBars = List.copyOf(bossBars);
    }
}
