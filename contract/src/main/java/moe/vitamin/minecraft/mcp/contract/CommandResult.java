package moe.vitamin.minecraft.mcp.contract;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of running a command on the server.
 *
 * <p>{@code dispatched} is not the same as "the command did what you wanted". Bukkit reports
 * whether a handler was found and ran without throwing, and plenty of commands report their own
 * failure by sending the sender a message while still returning true. That is why {@link
 * #output} exists: it is usually the only place the real answer appears.
 *
 * @param command        the command that was run, without a leading slash
 * @param executedAs     {@code CONSOLE}, or the player name it ran as
 * @param dispatched     whether a handler accepted it
 * @param output         lines the server logged while it ran
 * @param durationMillis how long the server spent on it, main thread included
 */
public record CommandResult(
        String command,
        String executedAs,
        boolean dispatched,
        List<String> output,
        long durationMillis) {

    /** The sender name used when a command runs from the console. */
    public static final String CONSOLE = "CONSOLE";

    public CommandResult {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(executedAs, "executedAs");
        output = output == null ? List.of() : List.copyOf(output);
    }
}
