package moe.vitamin.minecraft.mcp.contract;

import java.util.Map;
import java.util.Objects;

/** One captured Bukkit event. */
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
