package moe.vitamin.minecraft.mcp.bot.core;

/** The line protocol between this process and a bot runner. */
public final class RunnerProtocol {

    /** Field separator. */
    public static final char SEPARATOR = '\t';

    /** Separates repeated records inside one field — menu slots, received messages. */
    public static final char RECORD_SEPARATOR = '';

    /** Separates the fields of one such record. */
    public static final char UNIT_SEPARATOR = '';

    public static final String SPAWN = "spawn";
    public static final String DESPAWN = "despawn";
    public static final String MOVE = "move";
    public static final String BREAK = "break";
    public static final String COMMAND = "command";
    public static final String CHAT = "chat";
    public static final String POSITION = "position";
    public static final String SHUTDOWN = "shutdown";

    /** Right-click a block: {@code use <bot> <x> <y> <z> [face]}. */
    public static final String USE = "use";

    /** Right-click an entity: {@code use_entity <bot> <x> <y> <z> [radius] [type]}. */
    public static final String USE_ENTITY = "use_entity";

    /** Click a slot in the open menu: {@code click <bot> <slot> <left|right|shift_*>}. */
    public static final String CLICK = "click";

    /** Close the open menu: {@code close_menu <bot>}. */
    public static final String CLOSE_MENU = "close_menu";

    /** What menu the bot has open: {@code menu <bot>} → {@code ok menu <containerId> <title>}. */
    public static final String MENU = "menu";

    /**
     * Everything the client was told, as opposed to what the server holds: {@code inspect <bot>}.
     */
    public static final String INSPECT = "inspect";

    public static final String OK = "ok";
    public static final String ERROR = "err";

    /** Printed by the runner once it is ready to accept commands. */
    public static final String READY = "ready";

    private RunnerProtocol() {}

    public static String encode(String... fields) {
        return String.join(String.valueOf(SEPARATOR), fields);
    }

    public static String[] decode(String line) {
        return line.split(String.valueOf(SEPARATOR), -1);
    }

    /** Splits a field holding repeated records. */
    public static String[] records(String field) {
        return field == null || field.isEmpty()
                ? new String[0]
                : field.split(String.valueOf(RECORD_SEPARATOR), -1);
    }

    /** Splits one record into its fields. */
    public static String[] fields(String record) {
        return record.split(String.valueOf(UNIT_SEPARATOR), -1);
    }

    /** Strips every character this protocol gives meaning to. */
    public static String sanitize(String text) {
        return text == null ? "" : text
                .replace(SEPARATOR, ' ')
                .replace(RECORD_SEPARATOR, ' ')
                .replace(UNIT_SEPARATOR, ' ')
                .replace('\n', ' ')
                .replace('\r', ' ');
    }
}
