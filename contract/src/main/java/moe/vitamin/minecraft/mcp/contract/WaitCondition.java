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

    public WaitCondition {
        Objects.requireNonNull(type, "type");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public String string(String key, String fallback) {
        Object value = parameters.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public int integer(String key, int fallback) {
        Object value = parameters.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    public double decimal(String key, double fallback) {
        Object value = parameters.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
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
