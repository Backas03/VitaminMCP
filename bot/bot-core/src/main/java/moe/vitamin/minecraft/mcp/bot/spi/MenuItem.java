package moe.vitamin.minecraft.mcp.bot.spi;

/** One slot of a menu, as the client received it. */
public record MenuItem(
        int slot, int itemId, int amount, String name, String customModelData, String lore) {}
