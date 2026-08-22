package moe.vitamin.minecraft.mcp.contract;

import java.util.List;
import java.util.Objects;

/** The outcome of running a command on the server. */
public record CommandResult(
        String command,
        String executedAs,
        boolean dispatched,
        String reason,
        List<String> output,
        long durationMillis) {

    /** The sender name used when a command runs from the console. */
    public static final String CONSOLE = "CONSOLE";

    public CommandResult {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(executedAs, "executedAs");
        output = output == null ? List.of() : List.copyOf(output);
    }

    /** A command a handler took. */
    public static CommandResult dispatched(
            String command, String executedAs, List<String> output, long durationMillis) {
        return new CommandResult(command, executedAs, true, null, output, durationMillis);
    }

    /**
     * A command nothing took, and why.
     *
     * <p>Without the reason this is indistinguishable from a command that ran and did nothing:
     * both are {@code dispatched: false} with an empty {@code output}.
     */
    public static CommandResult refused(
            String command, String executedAs, String reason,
            List<String> output, long durationMillis) {
        return new CommandResult(
                command, executedAs, false, Objects.requireNonNull(reason, "reason"),
                output, durationMillis);
    }
}
