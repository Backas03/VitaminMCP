package moe.vitamin.minecraft.mcp.bot.spi;

/**
 * A boss bar as the client would draw it.
 *
 * @param progress 0..1, the fraction of the bar that is filled
 * @param color    one of Minecraft's six bar colours, or empty if the server did not say
 */
public record BossBar(String title, float progress, String color) {}
