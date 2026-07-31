package moe.vitamin.minecraft.mcp.bot.core;

/**
 * The line protocol between this process and a bot runner.
 *
 * <p>Bots live in a child process because one JVM cannot speak two Minecraft protocol versions
 * at once: every MCProtocolLib build uses the same package names, so two of them cannot share a
 * classpath. Testing 1.21.8 and 1.21.11 in one matrix therefore needs two processes, and a
 * process boundary is a far simpler thing to reason about than isolating class loaders around a
 * library that owns Netty threads.
 *
 * <p>Deliberately not JSON. Every message is one line of tab-separated fields, which needs no
 * parser on either side and keeps bot-core free of a JSON dependency it would otherwise carry
 * only for this. The fields are short and positional; the runner is not a public API.
 *
 * <pre>
 *   → spawn    Tester1
 *   ← ok       spawn   Tester1  -97.5  79.0  -107.5
 *   → spawn    Tester2  203.0.113.7        ← claim an address instead of using the real one
 *   ← ok       spawn   Tester2  -97.5  79.0  -107.5
 *   → break    Tester1  -97  78  -107
 *   ← ok       break
 *   → position Tester1
 *   ← ok       position  -97.5  79.0  -107.5
 *   ← err      spawn   Outdated client! Please use 1.21.11
 * </pre>
 *
 * <p>Trailing fields are optional, and an absent one means the same as an empty one: the
 * default. That is why the client IP goes last rather than beside the name.
 */
public final class RunnerProtocol {

    /** Field separator. Tab, because a kick reason can contain almost anything else. */
    public static final char SEPARATOR = '\t';

    /**
     * Separates repeated records inside one field — menu slots, received messages.
     *
     * <p>ASCII record/unit separators rather than anything printable. A menu button's name is
     * written by a plugin author and can hold any character a comma or pipe might be; these two
     * exist for exactly this and appear in Minecraft text essentially never. {@link #sanitize}
     * removes them anyway, because "essentially never" is not a guarantee.
     */
    public static final char RECORD_SEPARATOR = '';

    /** Separates the fields of one such record. */
    public static final char UNIT_SEPARATOR = '';

    // Commands.
    public static final String SPAWN = "spawn";
    public static final String DESPAWN = "despawn";
    public static final String MOVE = "move";
    public static final String BREAK = "break";
    public static final String COMMAND = "command";
    public static final String CHAT = "chat";
    public static final String POSITION = "position";
    public static final String SHUTDOWN = "shutdown";

    /** Right-click a block: {@code use <bot> <x> <y> <z> [face]}. Opens containers. */
    public static final String USE = "use";

    /**
     * Right-click an entity: {@code use_entity <bot> <x> <y> <z> [radius] [type]}.
     *
     * <p>Addressed by where it stands rather than by entity id. The id is invented by the server
     * and never leaves the protocol, so it is not something a scenario could have been written
     * against; the runner resolves the nearest match itself.
     */
    public static final String USE_ENTITY = "use_entity";

    /** Click a slot in the open menu: {@code click <bot> <slot> <left|right|shift_*>}. */
    public static final String CLICK = "click";

    /** Close the open menu: {@code close_menu <bot>}. */
    public static final String CLOSE_MENU = "close_menu";

    /**
     * What menu the bot has open: {@code menu <bot>} → {@code ok menu <containerId> <title>}.
     *
     * <p>Answered from the bot's side rather than the agent's on purpose. The agent says what
     * the <em>server</em> thinks is open; this says what the <em>client</em> was told. They
     * disagree exactly when a menu failed to reach the client, which is a bug worth being able
     * to see rather than one to be averaged away.
     */
    public static final String MENU = "menu";

    /**
     * Everything the client was told, as opposed to what the server holds: {@code inspect <bot>}.
     *
     * <p>Both halves answer questions the agent cannot. A menu painted with packets — which is
     * how a plugin using ProtocolLib or packetevents draws one — leaves the server-side
     * inventory empty, so the agent reports nothing while the player sees a full screen. And a
     * refusal the server sends the player ("you lack permission") never reaches the console, so
     * from the agent's side a command that was politely declined and one that did nothing look
     * identical.
     */
    public static final String INSPECT = "inspect";

    // Replies.
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

    /** Splits a field holding repeated records. Empty in, empty out — not one blank record. */
    public static String[] records(String field) {
        return field == null || field.isEmpty()
                ? new String[0]
                : field.split(String.valueOf(RECORD_SEPARATOR), -1);
    }

    /** Splits one record into its fields. */
    public static String[] fields(String record) {
        return record.split(String.valueOf(UNIT_SEPARATOR), -1);
    }

    /**
     * Strips every character this protocol gives meaning to.
     *
     * <p>Applied to anything a plugin author wrote — item names, lore, chat. A separator
     * arriving inside a value would shift every field after it, which is a corruption that
     * reads as wrong data rather than as an error.
     */
    public static String sanitize(String text) {
        return text == null ? "" : text
                .replace(SEPARATOR, ' ')
                .replace(RECORD_SEPARATOR, ' ')
                .replace(UNIT_SEPARATOR, ' ')
                .replace('\n', ' ')
                .replace('\r', ' ');
    }
}
