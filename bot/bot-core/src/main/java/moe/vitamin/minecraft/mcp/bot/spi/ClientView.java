package moe.vitamin.minecraft.mcp.bot.spi;

import java.util.List;

/**
 * Everything the client knows that the server will not report.
 *
 * <p>Two things live here for the same reason: neither survives on the server. A menu drawn with
 * packets — which is how a plugin using ProtocolLib or packetevents draws one — leaves the Bukkit
 * inventory empty, and a plugin's refusal is a message to the player and nothing else.
 *
 * @param menu       the open menu, or {@code null} if none
 * @param items      its occupied slots as they arrived on the wire
 * @param messages   what the server has said to this bot, oldest first. Action bar, title and
 *                   subtitle text is included, prefixed with where it appeared
 * @param bossBars   boss bars on screen now
 * @param scoreboard the sidebar scoreboard, or {@code null} when none is displayed
 */
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
