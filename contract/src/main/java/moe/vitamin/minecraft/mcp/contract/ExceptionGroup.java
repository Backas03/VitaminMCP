package moe.vitamin.minecraft.mcp.contract;

import java.util.Objects;

/** A distinct exception, collapsed across every occurrence of it. */
public record ExceptionGroup(
        String hash,
        String type,
        String message,
        long count,
        long firstSeen,
        long lastSeen,
        String stackTrace) {

    public ExceptionGroup {
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(type, "type");
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1 but was: " + count);
        }
    }

    /** A copy without the stack trace, for list responses. */
    public ExceptionGroup withoutStackTrace() {
        return stackTrace == null
                ? this
                : new ExceptionGroup(hash, type, message, count, firstSeen, lastSeen, null);
    }
}
