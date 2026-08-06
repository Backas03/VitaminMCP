package moe.vitamin.minecraft.mcp.agent.core;

import java.util.Locale;

/** How much of the agent's own activity is written to the server console. */
public enum ActivityLogging {

    /** One line per call as it arrives and one when it finishes, arguments included. */
    FULL,

    /** The same two lines, without arguments. */
    SUMMARY,

    /** Lifecycle, refused tokens and state changes only. */
    OFF;

    /** Whether calls are logged at all. */
    public boolean logsCalls() {
        return this != OFF;
    }

    /** Whether a logged call carries its arguments. */
    public boolean logsArguments() {
        return this == FULL;
    }

    /** Parses a config value, falling back to {@link #FULL} for anything unrecognised. */
    public static ActivityLogging parse(String raw) {
        if (raw == null) {
            return FULL;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "off", "none", "false" -> OFF;
            case "summary", "basic" -> SUMMARY;
            default -> FULL;
        };
    }
}
