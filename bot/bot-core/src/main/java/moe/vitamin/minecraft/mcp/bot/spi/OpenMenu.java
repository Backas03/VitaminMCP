package moe.vitamin.minecraft.mcp.bot.spi;

/** A menu the server has opened on the client, as the client sees it. */
public record OpenMenu(int containerId, String title) {}
