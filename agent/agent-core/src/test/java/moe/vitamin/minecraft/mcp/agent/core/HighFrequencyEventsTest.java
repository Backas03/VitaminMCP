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

        assertTrue(events.contains("PlayerMoveEvent"));
        assertTrue(events.contains("BlockPhysicsEvent"));
        assertTrue(events.contains("ChunkLoadEvent"));
        assertTrue(events.contains("VehicleMoveEvent"));
    }

    @Test
    void excludesTheTypesThatActuallyDominatedAnIdleServer() {
        HighFrequencyEvents events = HighFrequencyEvents.defaults();

        assertTrue(events.contains("BlockFromToEvent"));
        assertTrue(events.contains("ServerTickStartEvent"));
        assertTrue(events.contains("ServerTickEndEvent"));
        assertTrue(events.contains("GenericGameEvent"));
    }

    @Test
    void excludesTheTypesThatOnlyAppearWhenSomeoneIsPlaying() {
        HighFrequencyEvents events = HighFrequencyEvents.defaults();

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

        assertTrue(events.allowedInQuery("PlayerMoveEvent", List.of("PlayerMoveEvent")));
        assertTrue(events.allowedInQuery("PlayerMoveEvent", Set.of("PlayerMoveEvent", "BlockBreakEvent")));
    }

    @Test
    void configurationCanAddAndReinstateTypes() {
        HighFrequencyEvents events =
                HighFrequencyEvents.of(List.of("CustomSpamEvent"), List.of("ChunkLoadEvent"));

        assertTrue(events.contains("CustomSpamEvent"));
        assertFalse(events.contains("ChunkLoadEvent"));

        assertTrue(events.contains("PlayerMoveEvent"));
    }

    @Test
    void nullConfigurationLeavesTheDefaultsIntact() {
        HighFrequencyEvents events = HighFrequencyEvents.of(null, null);

        assertTrue(events.contains("PlayerMoveEvent"));
        assertFalse(events.excluded().isEmpty());
    }
}
