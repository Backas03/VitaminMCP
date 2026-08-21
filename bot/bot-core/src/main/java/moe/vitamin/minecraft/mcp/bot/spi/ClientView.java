package moe.vitamin.minecraft.mcp.bot.spi;

import java.util.List;

/** Everything the client knows that the server will not report. */
public record ClientView(
        OpenMenu menu,
        List<MenuItem> items,
        List<String> messages,
        List<BossBar> bossBars,
        Scoreboard scoreboard,
        Float health,
        Integer food,
        Integer experienceLevel,
        Integer totalExperience,
        Float experienceProgress,
        List<String> effects) {

    /** Compatibility constructor for the Java runner, which predates Stage 6 client stats. */
    public ClientView(
            OpenMenu menu,
            List<MenuItem> items,
            List<String> messages,
            List<BossBar> bossBars,
            Scoreboard scoreboard) {
        this(menu, items, messages, bossBars, scoreboard, null, null, null, null, null, List.of());
    }

    public ClientView {
        items = List.copyOf(items);
        messages = List.copyOf(messages);
        bossBars = List.copyOf(bossBars);
        effects = List.copyOf(effects);
    }
}
