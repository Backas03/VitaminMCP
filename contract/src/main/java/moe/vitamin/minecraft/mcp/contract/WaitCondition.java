package moe.vitamin.minecraft.mcp.contract;

import java.util.Map;
import java.util.Objects;

/** Something to wait for, described as data. */
public record WaitCondition(String type, Map<String, Object> parameters) {

    /** Advance a fixed number of server ticks. */
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
     * A player's state matches the fields given — any of {@code online}, {@code gameMode}, {@code
     * op}.
     */
    public static final String PLAYER_STATE = "player_state";

    /** A player has a plugin menu open, optionally one with a given title. */
    public static final String INVENTORY_OPEN = "inventory_open";

    /** A menu holds a given item, optionally at a given slot. */
    public static final String INVENTORY_CONTAINS = "inventory_contains";

    /**
     * A log line matching a regular expression has been written, optionally at or above a level.
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

    /** A numeric parameter. */
    public int integer(String key, int fallback) {
        Number number = number(key);
        return number == null ? fallback : number.intValue();
    }

    public double decimal(String key, double fallback) {
        Number number = number(key);
        return number == null ? fallback : number.doubleValue();
    }

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
