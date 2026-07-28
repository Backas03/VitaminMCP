package moe.vitamin.minecraft.mcp.contract;

/**
 * Severity of a captured log entry.
 *
 * <p>Deliberately a small fixed set rather than a mirror of Log4j2's level hierarchy: contract
 * must stay free of external types, and these five are the only distinctions a caller filtering
 * logs actually acts on.
 */
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
