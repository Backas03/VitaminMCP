package moe.vitamin.minecraft.mcp.contract;

import java.util.Objects;

/** One captured log line. */
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
