package moe.vitamin.minecraft.mcp.contract;

import java.util.List;
import java.util.Objects;

/**
 * What a player is looking at, slot by slot.
 *
 * <p>Exists for the question a menu plugin actually raises: the command ran, a GUI opened — is
 * the right thing in the right slot, with the right label? Nothing else can answer it. A
 * plugin's menu is a virtual inventory held by an open view; it is not stored on the player, so
 * no amount of reading NBT or replaying events will produce its contents.
 *
 * <p><b>Only occupied slots are listed.</b> A double chest menu is 54 slots and typically a
 * handful of buttons on a background of air; returning every empty slot would spend the response
 * budget on nothing. {@link #size} and {@link #occupiedSlots} still describe the whole thing, so
 * "slot 22 is empty" remains answerable — it is simply absent from {@link #items}.
 *
 * <p>No cursor, unlike the log and event queries. An inventory is bounded by the protocol at a
 * few dozen slots, so paging it would be ceremony; {@code limit} and {@link #truncated} cover the
 * pathological case. This matches {@code state_query}'s other kinds, which also read one bounded
 * thing rather than a stream.
 *
 * @param view          the view type, e.g. {@code CHEST}, {@code HOPPER}, {@code CRAFTING}.
 *                      {@code CRAFTING} means no menu is open — it is the player's own screen
 * @param title         the title as the player sees it, or {@code null} for the default
 * @param size          slots in the inventory that was read
 * @param occupiedSlots how many hold an item, whether or not all of them are listed
 * @param items         the occupied slots, in slot order
 * @param truncated     whether {@code items} was cut short by the limit
 */
public record InventorySnapshot(
        String view,
        String title,
        int size,
        int occupiedSlots,
        List<Item> items,
        boolean truncated) {

    /**
     * View types that mean "no menu is open" — the player's own screen, under its several names.
     *
     * <p>There is more than one because the name depends on how the player is playing:
     * {@code CRAFTING} in survival, {@code CREATIVE} in creative, {@code PLAYER} in some
     * versions. Treating only the first as "no menu" made every creative-mode player look like
     * they had a menu open, which turned {@code inventory_open} into a wait that returned
     * immediately and always — the exact silent no-op the wait exists to prevent.
     */
    public static final java.util.Set<String> NO_MENU_VIEWS =
            java.util.Set.of("CRAFTING", "CREATIVE", "PLAYER");

    public InventorySnapshot {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Whether a plugin menu is open, as opposed to the player's own inventory screen. */
    public boolean menuIsOpen() {
        return isMenu(view);
    }

    /** Whether a view type name denotes a real menu. Shared so both sides agree on the list. */
    public static boolean isMenu(String viewType) {
        return viewType != null && !NO_MENU_VIEWS.contains(viewType);
    }

    /**
     * One occupied slot.
     *
     * @param slot        index within the inventory that was read
     * @param material    Bukkit material name, e.g. {@code DIAMOND_SWORD}
     * @param amount      stack size
     * @param displayName the custom name, with colour codes as {@code §} sequences, or
     *                    {@code null} if the item has no custom name. Kept in legacy form
     *                    rather than stripped because for a menu the colour is part of what is
     *                    being tested — a caller that does not care can ignore the codes, but
     *                    one that does could not put them back
     * @param lore        custom lore lines, same encoding, empty if none
     * @param enchanted   whether it carries any enchantment, which is what gives a menu button
     *                    its glow
     */
    public record Item(
            int slot,
            String material,
            int amount,
            String displayName,
            List<String> lore,
            boolean enchanted) {

        public Item {
            Objects.requireNonNull(material, "material");
            lore = lore == null ? List.of() : List.copyOf(lore);
        }
    }
}
