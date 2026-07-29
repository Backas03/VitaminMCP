package moe.vitamin.minecraft.mcp.contract;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of waiting for a condition.
 *
 * <p>The snapshot is the point of this type. A test that times out having been told only "it
 * timed out" sends whoever reads it back to the server to work out why, by which time the
 * state that explains it is gone. Returning what the server was doing at the moment of failure
 * — the events it recorded and the lines it logged — usually contains the answer, and costs
 * nothing when the wait succeeds because it is only filled in when it does not
 * (docs/roadmap.md Stage 3).
 *
 * @param matched        whether the condition became true
 * @param condition      what was waited for, for a message that says what it wanted
 * @param elapsedMillis  wall-clock time spent waiting
 * @param ticksObserved  server ticks that elapsed, which is the meaningful unit here
 * @param recentEvents   events around the failure; empty when matched
 * @param recentLogs     log lines around the failure; empty when matched
 */
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
