package moe.vitamin.minecraft.mcp.contract;

import java.util.Map;
import java.util.Objects;

/**
 * One captured Bukkit event.
 *
 * <p>{@code type} is the simple class name ({@code PlayerJoinEvent}), not the fully qualified
 * one. That is what an LLM naturally writes when filtering, and the fully qualified name would
 * roughly double the size of every record in a response — a real cost given how tight the
 * token budget is on this data. Two event classes sharing a simple name across packages is the
 * accepted trade.
 *
 * <p>{@code cancelled} is captured rather than filtered on: listeners run at MONITOR with
 * {@code ignoreCancelled = false} precisely because a cancelled event is usually the most
 * interesting one when something is not working.
 *
 * @param sequence  position in the events stream; the value a {@link Cursor} resumes from
 * @param timestamp epoch milliseconds at capture
 * @param type      simple class name of the event
 * @param player    name of the player the event concerns, or {@code null}
 * @param cancelled whether the event was cancelled by the time it reached MONITOR
 * @param payload   event-specific detail; values are restricted to JSON primitives
 */
public record EventRecord(
        long sequence,
        long timestamp,
        String type,
        String player,
        boolean cancelled,
        Map<String, Object> payload)
        implements Sequenced {

    public EventRecord {
        Objects.requireNonNull(type, "type");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
