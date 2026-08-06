package moe.vitamin.minecraft.mcp.contract;

/** Severity of a captured log entry. */
public enum LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR;

    /** Whether this level is at least as severe as {@code threshold}. */
    public boolean atLeast(LogLevel threshold) {
        return ordinal() >= threshold.ordinal();
    }
}
