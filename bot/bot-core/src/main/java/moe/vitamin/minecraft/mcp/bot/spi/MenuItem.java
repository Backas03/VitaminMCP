package moe.vitamin.minecraft.mcp.bot.spi;

/**
 * One slot of a menu, as the client received it.
 *
 * @param itemId          registry index, not a name — the protocol carries no names and
 *                        MCProtocolLib ships no table to recover them. Use the agent's
 *                        {@code state_query} when the material matters and the server actually
 *                        holds the menu
 * @param customModelData the model data selector, string keys preferred over the first float
 */
public record MenuItem(
        int slot, int itemId, int amount, String name, String customModelData, String lore) {}
