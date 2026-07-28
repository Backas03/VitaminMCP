package moe.vitamin.minecraft.mcp.contract;

import java.util.Objects;

/**
 * One captured log line.
 *
 * <p>Named {@code LogEntry} rather than {@code LogRecord} to avoid colliding with
 * {@link java.util.logging.LogRecord}, which is on every Bukkit plugin's classpath.
 *
 * <p>A throwable is referenced by hash instead of being inlined. The same exception commonly
 * repeats hundreds of times, and carrying the stack trace on each copy would exhaust a response
 * budget on near-identical text. The hash resolves to an {@link ExceptionGroup} that reports the
 * repeat count once; the full trace is fetched only when explicitly asked for.
 *
 * @param sequence      position in the logs stream; the value a {@link Cursor} resumes from
 * @param timestamp     epoch milliseconds at capture
 * @param level         severity
 * @param logger        logger name, e.g. the plugin name
 * @param message       formatted message, without the stack trace
 * @param throwableHash key into the exception groups, or {@code null} if none was attached
 */
public record LogEntry(
        long sequence,
        long timestamp,
        LogLevel level,
        String logger,
        String message,
        String throwableHash)
        implements Sequenced {

    public LogEntry {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(logger, "logger");
        message = message == null ? "" : message;
    }

    /** Whether this entry carries an attached throwable. */
    public boolean hasThrowable() {
        return throwableHash != null;
    }
}
