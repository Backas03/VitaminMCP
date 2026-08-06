package moe.vitamin.minecraft.mcp.contract;

import java.util.List;
import java.util.Objects;

/** What a player is looking at, slot by slot. */
public record InventorySnapshot(
        String view,
        String title,
        int size,
        int occupiedSlots,
        List<Item> items,
        boolean truncated) {

    /**
     * View types that mean "no menu is open" — the player's own screen, under its several names.
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

    /** Whether a view type name denotes a real menu. */
    public static boolean isMenu(String viewType) {
        return viewType != null && !NO_MENU_VIEWS.contains(viewType);
    }

    /** One occupied slot. */
    public record Item(
            int slot,
            String material,
            int amount,
            String displayName,
            List<String> lore,
            boolean enchanted,
            Integer customModelData,
            ModelData modelData) {

        public Item {
            Objects.requireNonNull(material, "material");
            lore = lore == null ? List.of() : List.copyOf(lore);
        }
    }

    /** The {@code custom_model_data} component, as four parallel lists. */
    public record ModelData(
            List<Float> floats,
            List<Boolean> flags,
            List<String> strings,
            List<String> colors) {

        public ModelData {
            floats = floats == null ? List.of() : List.copyOf(floats);
            flags = flags == null ? List.of() : List.copyOf(flags);
            strings = strings == null ? List.of() : List.copyOf(strings);
            colors = colors == null ? List.of() : List.copyOf(colors);
        }

        /** Whether the component carries nothing, in which case it is not worth reporting. */
        public boolean carriesNothing() {
            return floats.isEmpty() && flags.isEmpty() && strings.isEmpty() && colors.isEmpty();
        }
    }
}
