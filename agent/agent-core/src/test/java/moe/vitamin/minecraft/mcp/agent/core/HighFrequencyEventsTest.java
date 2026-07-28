package moe.vitamin.minecraft.mcp.agent.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HighFrequencyEventsTest {

    @Test
    void excludesTheTypesTheDesignCallsOut() {
        HighFrequencyEvents events = HighFrequencyEvents.defaults();

        // docs/design.md §8 and roadmap 1b name these four explicitly.
        assertTrue(events.contains("PlayerMoveEvent"));
        assertTrue(events.contains("BlockPhysicsEvent"));
        assertTrue(events.contains("ChunkLoadEvent"));
        assertTrue(events.contains("VehicleMoveEvent"));
    }

    @Test
    void leavesOrdinaryEventsAlone() {
        HighFrequencyEvents events = HighFrequencyEvents.defaults();

        assertFalse(events.contains("PlayerJoinEvent"));
        assertFalse(events.contains("BlockBreakEvent"));
        assertFalse(events.contains("AsyncPlayerChatEvent"));
    }

    @Test
    void anUnfilteredQueryNeverReturnsHighFrequencyEvents() {
        HighFrequencyEvents events = HighFrequencyEvents.defaults();

        assertFalse(events.allowedInQuery("PlayerMoveEvent", null));
        assertFalse(events.allowedInQuery("PlayerMoveEvent", List.of()));
        assertTrue(events.allowedInQuery("BlockBreakEvent", null));
    }

    @Test
    void namingAHighFrequencyTypeIsWhatOptsIntoIt() {
        HighFrequencyEvents events = HighFrequencyEvents.defaults();

        // The whole point of the two-step flow: you can still get movement data, but only by
        // asking for it, never by accident.
        assertTrue(events.allowedInQuery("PlayerMoveEvent", List.of("PlayerMoveEvent")));
        assertTrue(events.allowedInQuery("PlayerMoveEvent", Set.of("PlayerMoveEvent", "BlockBreakEvent")));
    }

    @Test
    void configurationCanAddAndReinstateTypes() {
        HighFrequencyEvents events =
                HighFrequencyEvents.of(List.of("CustomSpamEvent"), List.of("ChunkLoadEvent"));

        assertTrue(events.contains("CustomSpamEvent"));
        assertFalse(events.contains("ChunkLoadEvent"));
        // Reinstating one type must not disturb the rest of the list.
        assertTrue(events.contains("PlayerMoveEvent"));
    }

    @Test
    void nullConfigurationLeavesTheDefaultsIntact() {
        HighFrequencyEvents events = HighFrequencyEvents.of(null, null);

        assertTrue(events.contains("PlayerMoveEvent"));
        assertFalse(events.excluded().isEmpty());
    }
}
