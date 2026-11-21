package moe.vitamin.minecraft.mcp.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventRateLimitTest {

    private static final long SECOND = 1_795_281_000_000L;

    @Test
    void aFloodingTypeKeepsTheFirstEventsOfEachSecondAndCountsTheRest() {
        EventRateLimit limit = new EventRateLimit(3, List.of());

        int kept = 0;
        for (int i = 0; i < 10; i++) {
            if (limit.admit("HopperInventorySearchEvent", false, SECOND + i)) {
                kept++;
            }
        }

        assertEquals(3, kept);
        assertEquals(Map.of("HopperInventorySearchEvent", 7L), limit.notKept());
        assertTrue(limit.admit("HopperInventorySearchEvent", false, SECOND + 1_000));
    }

    @Test
    void oneTypeFloodingLeavesEveryOtherTypeAlone() {
        EventRateLimit limit = new EventRateLimit(3, List.of());
        for (int i = 0; i < 100; i++) {
            limit.admit("EntityTeleportEvent", false, SECOND);
        }

        assertTrue(limit.admit("BlockRedstoneEvent", false, SECOND));
        assertEquals(97L, limit.totalNotKept());
    }

    @Test
    void anEventInvolvingAPlayerIsNeverLimited() {
        EventRateLimit limit = new EventRateLimit(1, List.of());
        for (int i = 0; i < 50; i++) {
            assertTrue(limit.admit("PlayerInteractEvent", true, SECOND));
        }
        assertEquals(Map.of(), limit.notKept());
    }

    @Test
    void aReinstatedTypeIsNeverLimited() {
        EventRateLimit limit = new EventRateLimit(1, List.of("InventoryMoveItemEvent"));
        for (int i = 0; i < 50; i++) {
            assertTrue(limit.admit("InventoryMoveItemEvent", false, SECOND));
        }
        assertFalse(limit.admit("EntityDamageEvent", false, SECOND)
                && limit.admit("EntityDamageEvent", false, SECOND));
    }

    @Test
    void zeroTurnsTheLimitOff() {
        EventRateLimit limit = new EventRateLimit(0, List.of());
        for (int i = 0; i < 1_000; i++) {
            assertTrue(limit.admit("EntityTeleportEvent", false, SECOND));
        }
        assertEquals(0L, limit.totalNotKept());
    }
}
