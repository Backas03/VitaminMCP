package moe.vitamin.minecraft.mcp.bot.runner;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;

/**
 * Reading what a menu button says.
 *
 * <p><b>A seam.</b> Item components arrived in 1.20.5 and the accessor was renamed on the way:
 * {@code getDataComponents()} up to 1.21.1, {@code getDataComponentsPatch()} from 1.21.2, with
 * the constants moving from {@code DataComponentType} to {@code DataComponentTypes} at the same
 * time. Nothing about what is being read changed at all.
 *
 * <p>The name and lore are what identify a button in practice — the item id is a registry index
 * the protocol carries no names for — so this is the difference between a readable menu and a
 * list of numbers.
 *
 * <p>This copy is the modern one: protocol 768 and later.
 */
final class ItemText {

    private ItemText() {}

    /** The item's custom name, falling back to its item name, or empty. */
    static String nameOf(ItemStack item) {
        var components = item.getDataComponentsPatch();
        if (components == null) {
            return "";
        }
        var custom = components.get(DataComponentTypes.CUSTOM_NAME);
        if (custom == null) {
            custom = components.get(DataComponentTypes.ITEM_NAME);
        }
        return custom == null ? "" : PlainTextComponentSerializer.plainText().serialize(custom);
    }

    /** The lore lines joined with {@code |}, or empty. */
    static String loreOf(ItemStack item) {
        var components = item.getDataComponentsPatch();
        if (components == null) {
            return "";
        }
        var lore = components.get(DataComponentTypes.LORE);
        if (lore == null || lore.isEmpty()) {
            return "";
        }
        return lore.stream()
                .map(line -> PlainTextComponentSerializer.plainText().serialize(line))
                .collect(java.util.stream.Collectors.joining(" | "));
    }

    /** The model data selector: string keys preferred over the first float, or empty. */
    static String modelDataOf(ItemStack item) {
        var components = item.getDataComponentsPatch();
        if (components == null) {
            return "";
        }
        var model = components.get(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (model == null) {
            return "";
        }
        // Strings first: a pack keyed on them is the modern idiom, and the floats are often
        // absent entirely when it is.
        if (model.strings() != null && !model.strings().isEmpty()) {
            return String.join(",", model.strings());
        }
        if (model.floats() != null && !model.floats().isEmpty()) {
            return String.valueOf(model.floats().get(0));
        }
        return "";
    }
}
