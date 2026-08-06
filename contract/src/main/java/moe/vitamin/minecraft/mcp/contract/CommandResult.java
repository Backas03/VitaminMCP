package moe.vitamin.minecraft.mcp.contract;

import java.util.List;
import java.util.Objects;

/** The outcome of running a command on the server. */
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
