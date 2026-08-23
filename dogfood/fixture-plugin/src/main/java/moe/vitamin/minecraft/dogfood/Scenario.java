package moe.vitamin.minecraft.dogfood;

import java.util.Locale;

/**
 * Which fault this server is running with.
 *
 * <p>One at a time, on purpose. A plugin with four things wrong at once is a different exercise:
 * the debugger finds the loudest one and stops, and the harness learns nothing about the quiet
 * ones. Selected by {@code scenario} in config.yml.
 */
public enum Scenario {

    /** Everything works. The control — a run against this should find nothing to report. */
    NONE,

    /** {@code /top} answers, but not on the thread or the tick the caller is watching. */
    ASYNC_REPLY,

    /** {@code /shop} declines, somewhere the console cannot see. */
    SILENT_REFUSAL,

    /** A joining player is not fully theirs yet, and the plugin does not say so. */
    JOIN_LOCKOUT,

    /** The join kit reaches most players. */
    SILENT_LISTENER,

    /** {@code /kit} is switched off, and said so once. */
    DISABLED_FEATURE,

    /** A config key is printed at startup but never reaches the command it claims to control. */
    DECORATED_CONFIG,

    /** {@code plugin.yml} declares the shop permission, but the command forgets to check it. */
    UNENFORCED_PERMISSION;

    static Scenario parse(String name) {
        if (name == null || name.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            return NONE;
        }
    }

    /** The name as it is written in config.yml. */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
