package moe.vitamin.minecraft.mcp.agent.core;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class EventRateLimit {

    public static final int DEFAULT_PER_SECOND = 200;

    private final int perSecond;
    private final Set<String> exempt;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    public EventRateLimit(int perSecond, Collection<String> exempt) {
        this.perSecond = perSecond;
        this.exempt = exempt == null ? Set.of() : Set.copyOf(exempt);
    }

    public boolean admit(String type, boolean involvesPlayer, long timestampMillis) {
        if (perSecond <= 0 || involvesPlayer || exempt.contains(type)) {
            return true;
        }
        return windows.computeIfAbsent(type, key -> new Window())
                .admit(Math.floorDiv(timestampMillis, 1000L), perSecond);
    }

    public Map<String, Long> notKept() {
        Map<String, Long> counts = new TreeMap<>();
        windows.forEach((type, window) -> {
            long refused = window.refused();
            if (refused > 0) {
                counts.put(type, refused);
            }
        });
        return counts;
    }

    public long totalNotKept() {
        return notKept().values().stream().mapToLong(Long::longValue).sum();
    }

    public int perSecond() {
        return perSecond;
    }

    private static final class Window {
        private long second = Long.MIN_VALUE;
        private int admitted;
        private long refused;

        synchronized boolean admit(long now, int limit) {
            if (now != second) {
                second = now;
                admitted = 0;
            }
            if (admitted < limit) {
                admitted++;
                return true;
            }
            refused++;
            return false;
        }

        synchronized long refused() {
            return refused;
        }
    }
}
