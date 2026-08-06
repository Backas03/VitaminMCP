package moe.vitamin.minecraft.mcp.bot.runner;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;

/** Reading what a menu button says. */
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

        if (model.strings() != null && !model.strings().isEmpty()) {
            return String.join(",", model.strings());
        }
        if (model.floats() != null && !model.floats().isEmpty()) {
            return String.valueOf(model.floats().get(0));
        }
        return "";
    }
}
