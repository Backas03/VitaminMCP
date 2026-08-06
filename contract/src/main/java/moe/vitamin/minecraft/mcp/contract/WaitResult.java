package moe.vitamin.minecraft.mcp.contract;

import java.util.List;
import java.util.Objects;

/** The outcome of waiting for a condition. */
public record WaitResult(
        boolean matched,
        String condition,
        long elapsedMillis,
        int ticksObserved,
        List<EventRecord> recentEvents,
        List<LogEntry> recentLogs) {

    public WaitResult {
        Objects.requireNonNull(condition, "condition");
        recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
        recentLogs = recentLogs == null ? List.of() : List.copyOf(recentLogs);
    }

    public static WaitResult matched(String condition, long elapsedMillis, int ticks) {
        return new WaitResult(true, condition, elapsedMillis, ticks, List.of(), List.of());
    }
}
