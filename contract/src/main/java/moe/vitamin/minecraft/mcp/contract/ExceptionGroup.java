package moe.vitamin.minecraft.mcp.contract;

import java.util.Objects;

/**
 * A distinct exception, collapsed across every occurrence of it.
 *
 * <p>Grouping is what makes this tool usable in practice. A server that has been up overnight
 * will happily hold the same NPE ten thousand times; listing them individually buries the three
 * other exceptions that actually matter. Reporting "this one, ×342, first seen at 03:11" puts
 * the useful information first.
 *
 * <p>{@code stackTrace} is {@code null} unless the caller explicitly asked for it. That keeps
 * a list of exceptions cheap enough to always start from.
 *
 * @param hash       stable identity derived from the stack frames, not the message
 * @param type       exception class name, e.g. {@code java.lang.NullPointerException}
 * @param message    the exception's own message, or {@code null}
 * @param count      how many times this exception has been seen
 * @param firstSeen  epoch milliseconds of the first occurrence
 * @param lastSeen   epoch milliseconds of the most recent occurrence
 * @param stackTrace the full trace, or {@code null} when not requested
 */
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
