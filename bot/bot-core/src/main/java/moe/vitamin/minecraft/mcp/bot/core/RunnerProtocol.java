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

    // Commands.
    public static final String SPAWN = "spawn";
    public static final String DESPAWN = "despawn";
    public static final String MOVE = "move";
    public static final String BREAK = "break";
    public static final String COMMAND = "command";
    public static final String CHAT = "chat";
    public static final String POSITION = "position";
    public static final String SHUTDOWN = "shutdown";

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
}
