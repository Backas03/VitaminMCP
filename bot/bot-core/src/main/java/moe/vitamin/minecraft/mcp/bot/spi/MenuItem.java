package moe.vitamin.minecraft.mcp.bot.spi;

/**
 * One slot of a menu, as the client received it.
 *
 * <p>{@code itemId} is the item's namespaced name — {@code minecraft:diamond_sword}. It used to be
 * the protocol's numeric id, which is a different number in every protocol version and so was
 * never something a scenario could be written against. The Java bot runner has no name table and
 * still reports the number, spelled as a string, until it is retired.
 */
public record MenuItem(
        int slot, String itemId, int amount, String name, String customModelData, String lore) {}
