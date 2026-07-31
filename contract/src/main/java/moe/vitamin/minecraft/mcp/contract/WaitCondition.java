package moe.vitamin.minecraft.mcp.contract;

import java.util.Map;
import java.util.Objects;

/**
 * Something to wait for, described as data.
 *
 * <p>Kept as a type name plus a parameter bag rather than a class per condition so that the
 * whole thing survives the trip from a JSON tool call, through contract, to the agent — where
 * it is evaluated — without contract needing a JSON library or a new type for every condition
 * anyone thinks of.
 *
 * <p>The reason conditions exist at all is that the alternative is sleeping. A scenario that
 * sleeps is guessing about timing, and a guess that is right on a quiet server is wrong on a
 * busy one; that is the whole mechanism by which flaky tests are produced (docs/roadmap.md
 * Stage 3).
 */
public record WaitCondition(String type, Map<String, Object> parameters) {

    /** Advance a fixed number of server ticks. The one condition that is about time, not state. */
    public static final String TICKS = "ticks";

    /** A block is a given material. */
    public static final String BLOCK_IS = "block_is";

    /** A block is anything but a given material — how "did it break" is asked. */
    public static final String BLOCK_IS_NOT = "block_is_not";

    /** An event of a type has been captured, optionally by a player, since a cursor. */
    public static final String EVENT = "event";

    /** A player is connected. */
    public static final String PLAYER_ONLINE = "player_online";

    /** A player is no longer connected. */
    public static final String PLAYER_OFFLINE = "player_offline";

    /** A player is within a distance of a point. */
    public static final String PLAYER_NEAR = "player_near";

    /**
     * A player's state matches the fields given — any of {@code online}, {@code gameMode},
     * {@code op}.
     *
     * <p>One condition for the family rather than one per attribute, because the reason to wait
     * is the same in every case: these change asynchronously. {@code /op} resolves a name to a
     * UUID before it takes effect, so a check fired immediately after the command sees the old
     * value and fails for reasons that have nothing to do with what was being tested.
     */
    public static final String PLAYER_STATE = "player_state";

    /**
     * A player has a plugin menu open, optionally one with a given title.
     *
     * <p>The precondition for reading a menu at all. Opening one is not synchronous with the
     * command that caused it — the plugin may hop a tick, or wait on a database — so a read
     * fired straight after the command sees the player's own inventory screen and reports an
     * empty menu, which looks exactly like a menu that failed to populate.
     */
    public static final String INVENTORY_OPEN = "inventory_open";

    /**
     * A menu holds a given item, optionally at a given slot.
     *
     * <p>Separate from {@link #INVENTORY_OPEN} because the two failure modes are different and
     * a test wants to tell them apart. A plugin that opens an empty menu and fills it a tick
     * later passes "is it open" while the buttons are still missing; waiting for the button
     * itself is what makes the subsequent assertions meaningful rather than lucky.
     */
    public static final String INVENTORY_CONTAINS = "inventory_contains";

    /**
     * A log line matching a regular expression has been written, optionally at or above a level.
     *
     * <p>The condition for waiting on work that leaves no trace in world or player state. A
     * plugin loading a player's data asynchronously is the common case: nothing observable
     * changes when it finishes, so every other condition here is blind to it, and the only
     * honest signal is the line the plugin logs on its way out.
     *
     * <p>Without this the alternative is {@code ticks}, which is a sleep wearing a different
     * name — and a scenario that waits a fixed number of ticks for an async load is calibrated
     * to the machine that wrote it, which is exactly what the rest of this design refuses to do.
     */
    public static final String LOG_MATCHES = "log_matches";

    public WaitCondition {
        Objects.requireNonNull(type, "type");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public String string(String key, String fallback) {
        Object value = parameters.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /**
     * A numeric parameter.
     *
     * <p>Accepts {@code "20"} as readily as {@code 20}. These parameters cross a JSON boundary
     * and the tool that forwards them cannot say what type each should be — the agent owns that
     * knowledge, and it is not published where the caller's client can act on it. So a number
     * sometimes arrives quoted.
     *
     * <p>It used to fall back to the default in that case, silently. {@code wait_for} with
     * {@code count: 20} therefore waited one tick and reported success: a wait that does not
     * wait, which is worse than no wait because it looks like protection. Refusing an
     * unparseable value rather than defaulting is the other half of that lesson.
     *
     * @throws IllegalArgumentException if the key is present but is not a number
     */
    public int integer(String key, int fallback) {
        Number number = number(key);
        return number == null ? fallback : number.intValue();
    }

    public double decimal(String key, double fallback) {
        Number number = number(key);
        return number == null ? fallback : number.doubleValue();
    }

    /** @return the value as a number, or {@code null} if the key was not supplied */
    private Number number(String key) {
        Object value = parameters.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number;
        }
        try {
            return Double.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "'" + key + "' must be a number but was '" + value + "'");
        }
    }

    /** Whether a key was supplied at all, so absent and false stay distinguishable. */
    public boolean has(String key) {
        return parameters.containsKey(key);
    }

    public boolean bool(String key, boolean fallback) {
        Object value = parameters.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    /** A short human description, used in failure messages so a timeout says what it wanted. */
    public String describe() {
        return parameters.isEmpty() ? type : type + " " + parameters;
    }
}
