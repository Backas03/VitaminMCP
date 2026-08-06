package moe.vitamin.minecraft.mcp.agent.core;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** The event types that would otherwise drown out everything else. */
public final class HighFrequencyEvents {

    /**
     * Measured against Paper 1.20.4, idle, zero players: 16,067 events in 72 seconds (~223/s)
     * before this list was widened.
     */
    private static final Set<String> DEFAULT_EXCLUDED = Set.of(

            "ServerTickStartEvent",
            "ServerTickEndEvent",

            "PlayerMoveEvent",
            "EntityMoveEvent",
            "VehicleMoveEvent",
            "VehicleUpdateEvent",
            "PlayerChangedMainHandEvent",

            "BlockPhysicsEvent",
            "BlockCanBuildEvent",
            "BlockFromToEvent",
            "FluidLevelChangeEvent",
            "BlockFormEvent",

            "GenericGameEvent",

            "ChunkLoadEvent",
            "ChunkUnloadEvent",
            "ChunkPopulateEvent",
            "EntitiesLoadEvent",
            "EntitiesUnloadEvent",
            "EntityAddToWorldEvent",
            "EntityRemoveFromWorldEvent",
            "AsyncStructureGenerateEvent",
            "AsyncStructureSpawnEvent",

            "EntityAirChangeEvent",
            "FoodLevelChangeEvent",
            "ItemDespawnEvent",
            "EntityPathfindEvent",
            "EntityInsideBlockEvent",
            "EntityJumpEvent",
            "EntityPoseChangeEvent",
            "EntityExhaustionEvent",
            "StriderTemperatureChangeEvent",

            "PreCreatureSpawnEvent",
            "PlayerNaturallySpawnCreaturesEvent",
            "PlayerChunkLoadEvent",
            "PlayerChunkUnloadEvent",
            "PlayerTrackEntityEvent",
            "PlayerUntrackEntityEvent",
            "PlayerArmSwingEvent",
            "BlockBreakProgressUpdateEvent");

    private final Set<String> excluded;

    private HighFrequencyEvents(Set<String> excluded) {
        this.excluded = Set.copyOf(excluded);
    }

    /** The built-in exclusion list. */
    public static HighFrequencyEvents defaults() {
        return new HighFrequencyEvents(DEFAULT_EXCLUDED);
    }

    /** The built-in list, adjusted by configuration. */
    public static HighFrequencyEvents of(Collection<String> additional, Collection<String> reinstated) {
        Set<String> result = new LinkedHashSet<>(DEFAULT_EXCLUDED);
        if (additional != null) {
            result.addAll(additional);
        }
        if (reinstated != null) {
            result.removeAll(reinstated);
        }
        return new HighFrequencyEvents(result);
    }

    /** Whether {@code simpleTypeName} is considered high frequency. */
    public boolean contains(String simpleTypeName) {
        return excluded.contains(simpleTypeName);
    }

    /**
     * Whether a query that asked for {@code requestedTypes} should include {@code simpleTypeName}.
     */
    public boolean allowedInQuery(String simpleTypeName, Collection<String> requestedTypes) {
        if (requestedTypes != null && requestedTypes.contains(simpleTypeName)) {
            return true;
        }
        return !contains(simpleTypeName);
    }

    /** The effective exclusion list, for reporting back through {@code server_info}. */
    public Set<String> excluded() {
        return excluded;
    }
}
