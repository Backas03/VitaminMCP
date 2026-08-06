package moe.vitamin.minecraft.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageTest {

    @Test
    void emptyPageReportsNoLossAndNoMore() {
        Page<String> page = Page.empty();

        assertTrue(page.items().isEmpty());
        assertFalse(page.hasMore());
        assertFalse(page.truncated());
        assertEquals(0L, page.dropped());
    }

    @Test
    void isDetachedFromTheListItWasBuiltFrom() {
        List<String> source = new ArrayList<>(List.of("a"));
        Page<String> page = new Page<>(source, null, false, 0L);

        source.add("b");

        assertEquals(List.of("a"), page.items());
    }

    @Test
    void itemsAreUnmodifiable() {
        Page<String> page = new Page<>(List.of("a"), null, false, 0L);

        assertThrows(UnsupportedOperationException.class, () -> page.items().add("b"));
    }

    @Test
    void truncationAndLossAreIndependent() {

        Page<String> budgetLimited = new Page<>(List.of("a"), "events:1", true, 0L);
        Page<String> lossy = new Page<>(List.of("a"), null, false, 17L);

        assertTrue(budgetLimited.hasMore());
        assertEquals(0L, budgetLimited.dropped());

        assertFalse(lossy.hasMore());
        assertEquals(17L, lossy.dropped());
    }

    @Test
    void rejectsANegativeDropCount() {
        assertThrows(IllegalArgumentException.class, () -> new Page<>(List.of(), null, false, -1L));
    }
}
