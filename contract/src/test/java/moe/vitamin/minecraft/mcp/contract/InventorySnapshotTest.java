package moe.vitamin.minecraft.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InventorySnapshotTest {

    /**
     * The bug this pins down cost a wait its meaning.
     *
     * <p>A player with nothing open does not have one view type — it depends on how they are
     * playing. Only {@code CRAFTING} was excluded at first, so every creative-mode player looked
     * like they had a menu open: {@code wait_for inventory_open} returned on the first tick,
     * always, and the scenario went on to read a menu that had not opened yet. It passed on a
     * fast server and would have failed on a slow one, which is the definition of the flakiness
     * the wait exists to remove.
     */
    @ParameterizedTest
    @ValueSource(strings = {"CRAFTING", "CREATIVE", "PLAYER"})
    void aPlayersOwnScreenIsNotAMenu(String view) {
        assertFalse(InventorySnapshot.isMenu(view), view + " is the player's own screen");
        assertFalse(snapshotOf(view).menuIsOpen());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHEST", "HOPPER", "DISPENSER", "BARREL", "SHULKER_BOX"})
    void aContainerIsAMenu(String view) {
        assertTrue(InventorySnapshot.isMenu(view));
        assertTrue(snapshotOf(view).menuIsOpen());
    }

    @Test
    void anUnknownViewIsTreatedAsAMenu() {
        // A view type we have never heard of is far more likely to be a plugin's custom screen
        // than a new name for the player's own, and calling it a menu fails loudly rather than
        // making a wait silently return.
        assertTrue(InventorySnapshot.isMenu("SOME_FUTURE_TYPE"));
    }

    @Test
    void aMissingViewIsNotAMenu() {
        assertFalse(InventorySnapshot.isMenu(null));
    }

    @Test
    void loreAndItemsAreNeverNull() {
        InventorySnapshot.Item item = new InventorySnapshot.Item(0, "STONE", 1, null, null, false);
        assertTrue(item.lore().isEmpty());
        assertTrue(new InventorySnapshot("CHEST", null, 27, 0, null, false).items().isEmpty());
    }

    private static InventorySnapshot snapshotOf(String view) {
        return new InventorySnapshot(view, null, 27, 0, List.of(), false);
    }
}
