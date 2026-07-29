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
    void excludesTheTypesThatActuallyDominatedAnIdleServer() {
        HighFrequencyEvents events = HighFrequencyEvents.defaults();

        // Measured on Paper 1.20.4 with nobody online: these four accounted for roughly
        // 14,000 of 16,067 events in 72 seconds. The design's examples did not name them, and
        // leaving them in lapped the buffer on an idle server.
        assertTrue(events.contains("BlockFromToEvent"));
        assertTrue(events.contains("ServerTickStartEvent"));
        assertTrue(events.contains("ServerTickEndEvent"));
        assertTrue(events.contains("GenericGameEvent"));
    }

    @Test
    void excludesTheTypesThatOnlyAppearWhenSomeoneIsPlaying() {
        HighFrequencyEvents events = HighFrequencyEvents.defaults();

        // An idle server never shows these, so they survived the first pass. One player for
        // nine seconds produced 9,694 events, 8,710 of them PreCreatureSpawnEvent alone.
        assertTrue(events.contains("PreCreatureSpawnEvent"));
        assertTrue(events.contains("PlayerChunkLoadEvent"));
        assertTrue(events.contains("PlayerArmSwingEvent"));
    }

    @Test
    void leavesOrdinaryEventsAlone() {
        HighFrequencyEvents events = HighFrequencyEvents.defaults();

        assertFalse(events.contains("PlayerJoinEvent"));
        assertFalse(events.contains("BlockBreakEvent"));
        assertFalse(events.contains("AsyncPlayerChatEvent"));
        // The pre-spawn check is excluded; what actually spawned is not.
        assertFalse(events.contains("CreatureSpawnEvent"));
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
