package moe.vitamin.minecraft.mcp.agent.core;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The event types that would otherwise drown out everything else.
 *
 * <p>Volume is the single biggest risk in this design (docs/design.md §8). {@code
 * PlayerMoveEvent} fires roughly twenty times per second per player and {@code
 * BlockPhysicsEvent} can fire thousands of times in one tick. Left alone, these types both
 * exhaust a response budget and lap the ring buffer within seconds, evicting the handful of
 * records anyone actually wanted.
 *
 * <p>The exclusion is applied in two places, which is what reconciles design.md §8 ("캡처는
 * 전부, 조회는 화이트리스트") with roadmap 1b (which lists the exclusion under the capture
 * engine):
 *
 * <ul>
 *   <li><b>Capture</b> — skipped by default. Capturing BlockPhysicsEvent would evict the whole
 *       buffer faster than any query could read it, so "capture everything" is offered as an
 *       opt-in rather than imposed as a default that destroys the data it collects.
 *   <li><b>Query</b> — excluded from results even when captured, unless the caller names the
 *       type explicitly. This is the whitelist half, and it still applies to whatever a server
 *       operator chose to turn on.
 * </ul>
 *
 * <p>Matching is on the simple class name so that a Paper or plugin variant sitting in a
 * different package is still recognised.
 */
public final class HighFrequencyEvents {

    private static final Set<String> DEFAULT_EXCLUDED = Set.of(
            // Movement — per-player, every tick.
            "PlayerMoveEvent",
            "EntityMoveEvent",
            "VehicleMoveEvent",
            "VehicleUpdateEvent",
            "PlayerChangedMainHandEvent",

            // Block physics — thousands per tick during redstone or water flow.
            "BlockPhysicsEvent",
            "BlockCanBuildEvent",

            // Chunk streaming — bursts on every player move across a chunk border.
            "ChunkLoadEvent",
            "ChunkUnloadEvent",

            // Per-tick entity bookkeeping.
            "EntityAirChangeEvent",
            "FoodLevelChangeEvent",
            "ItemDespawnEvent");

    private final Set<String> excluded;

    private HighFrequencyEvents(Set<String> excluded) {
        this.excluded = Set.copyOf(excluded);
    }

    /** The built-in exclusion list. */
    public static HighFrequencyEvents defaults() {
        return new HighFrequencyEvents(DEFAULT_EXCLUDED);
    }

    /**
     * The built-in list, adjusted by configuration.
     *
     * @param additional  extra simple names to treat as high frequency
     * @param reinstated  simple names to drop from the exclusion list, so an operator can pull
     *                    a specific type back into normal capture without turning the whole
     *                    protection off
     */
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
     * Whether a query that asked for {@code requestedTypes} should include {@code
     * simpleTypeName}.
     *
     * <p>Naming a high-frequency type explicitly is what opts into it. An unfiltered query
     * never returns them, which is what keeps {@code events_query} useful by default.
     *
     * @param requestedTypes types the caller named, or {@code null}/empty for "no filter"
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
