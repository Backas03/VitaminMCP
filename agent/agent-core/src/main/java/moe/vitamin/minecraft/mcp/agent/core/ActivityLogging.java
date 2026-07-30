package moe.vitamin.minecraft.mcp.agent.core;

import java.util.Locale;

/**
 * How much of the agent's own activity is written to the server console.
 *
 * <p>An MCP agent is invisible by default in a way nothing else on a server is: it reaches into
 * internals, and — with read-only off — runs console commands, while the only trace on the
 * console is the plugin loading. An operator watching their own server should be able to see
 * what it is being asked to do, which is why {@link #FULL} is the default rather than an
 * opt-in.
 *
 * <p>Two things are logged regardless of this setting, because they are the ones an operator
 * needs after the fact rather than during: a rejected token, and every state-changing tool
 * call. Turning activity logging off makes the console quiet, not unaccountable.
 */
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

    /**
     * Parses a config value, falling back to {@link #FULL} for anything unrecognised.
     *
     * <p>A typo in this key must not stop the server from starting — unlike the auth token,
     * nothing here is load-bearing for safety, and the safe reading of an unclear value is the
     * more verbose one.
     */
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
