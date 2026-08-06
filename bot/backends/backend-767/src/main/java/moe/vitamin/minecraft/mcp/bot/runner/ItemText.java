package moe.vitamin.minecraft.mcp.bot.runner;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;

/** Reading what a menu button says, on protocol 767. */
final class ItemText {

    private ItemText() {}

    static String nameOf(ItemStack item) {
        var components = item.getDataComponents();
        if (components == null) {
            return "";
        }
        var custom = components.get(DataComponentType.CUSTOM_NAME);
        if (custom == null) {
            custom = components.get(DataComponentType.ITEM_NAME);
        }
        return custom == null ? "" : PlainTextComponentSerializer.plainText().serialize(custom);
    }

    static String loreOf(ItemStack item) {
        var components = item.getDataComponents();
        if (components == null) {
            return "";
        }
        var lore = components.get(DataComponentType.LORE);
        if (lore == null || lore.isEmpty()) {
            return "";
        }
        return lore.stream()
                .map(line -> PlainTextComponentSerializer.plainText().serialize(line))
                .collect(java.util.stream.Collectors.joining(" | "));
    }

    /** The numeric selector, which is all this version's custom model data is. */
    static String modelDataOf(ItemStack item) {
        var components = item.getDataComponents();
        if (components == null) {
            return "";
        }
        Integer model = components.get(DataComponentType.CUSTOM_MODEL_DATA);
        return model == null ? "" : String.valueOf(model);
    }
}
