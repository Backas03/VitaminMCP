package moe.vitamin.minecraft.mcp.agent.core;

import java.util.Locale;

/**
 * Why the server accepted nothing, worked out from what it will say about the sender.
 *
 * <p>Paper answers a player the same way whether the command does not exist or the player may
 * not use it. {@code CraftServer#dispatchCommand} parses the line with brigadier against that
 * sender's own source; a node whose {@code requires} predicate says no is pruned, the parse ends
 * with no nodes, and dispatch returns {@code false} without telling anyone — not the caller, and
 * not even the player, who gets no "unknown command" message. Measured on Paper 1.21.8:
 * {@code /list} from a non-op and {@code /notacommand} from anyone are the same {@code false}
 * with the same empty output.
 *
 * <p>So the false is re-examined here rather than reported bare. The console is allowed every
 * command, which makes a failure there unambiguous. For a player it comes down to the permission
 * the refusal would have been about — the command's own if the Bukkit map knows it, otherwise the
 * {@code minecraft.command.<name>} node Paper puts in front of a vanilla command.
 *
 * <p>Where that gate sits is Paper's to move, and it has: on 1.21.8 a non-op is refused
 * {@code /list}, on 1.21.1 the same call runs. Nothing here depends on which — the permission is
 * asked about rather than assumed, so a version that does not gate a command simply never reaches
 * this class for it.
 *
 * <p>The trap is that {@code hasPermission} answers for a node nobody defined: an undefined node
 * takes the default, which is op. Ask a non-op about {@code minecraft.command.nosuchthing} and it
 * says no, which would turn every unknown command into a permission refusal. That is why
 * {@link Standing} separates "the server defines this and the sender lacks it" from "nothing
 * defines it, so the answer means nothing".
 */
record CommandRefusal(
        String label,
        String sender,
        boolean console,
        boolean known,
        String permission,
        Standing standing) {

    /** What the permission evidence is worth. */
    enum Standing {
        /** The sender has it, so permission is not why nothing ran. */
        HELD,
        /** The server defines it and the sender does not have it. */
        LACKED,
        /** Nothing defines it, so the sender's answer for it says nothing either way. */
        UNDEFINED,
        /** The command is registered and asks for no permission at all. */
        OPEN
    }

    /** The command word, as the dispatcher matched it: lower-cased, without any namespace. */
    static String label(String commandLine) {
        String first = commandLine.trim().split("\s+", 2)[0].toLowerCase(Locale.ENGLISH);
        int namespace = first.indexOf(':');
        return namespace < 0 ? first : first.substring(namespace + 1);
    }

    /**
     * What the permission answer is worth, given whether the server defines the node.
     *
     * <p>An op is treated as holding an undefined node rather than as unknowable, because an op
     * clears every gate a vanilla command puts up: nothing ran for them means nothing exists.
     */
    static Standing standing(boolean defined, boolean holds, boolean op) {
        if (defined) {
            return holds ? Standing.HELD : Standing.LACKED;
        }
        return op ? Standing.HELD : Standing.UNDEFINED;
    }

    /** The sentence that goes back to the caller in place of a bare {@code false}. */
    String explain() {
        if (console) {
            return "Nothing ran: this server has no command named '" + label + "'. The console "
                    + "is allowed every command, so nothing was refused.";
        }

        return switch (standing) {
            case LACKED -> "Nothing ran: '" + label + "' was refused before it executed, because "
                    + sender + " does not have " + permission + ". This is what a real player "
                    + "without that permission gets, so it is a result rather than a fault. "
                    + "Vanilla commands are op-only by default — run it as the console, or op "
                    + "the player, when the permission is not what is being tested.";

            case UNDEFINED -> "Nothing ran: nothing on this server answers to '" + label + "' for "
                    + sender + ". No command by that name is registered and no " + permission
                    + " permission is defined, so it is most likely not a command at all — but "
                    + sender + " is not op, so a command that hides itself from them would look "
                    + "the same. Run it as the console to tell the two apart.";

            case HELD, OPEN -> known
                    ? "Nothing ran: the server knows '" + label + "' and " + sender + " may use "
                            + "it, yet the command dispatcher matched nothing. A command "
                            + "registered into the Bukkit map without the dispatcher being told "
                            + "looks like this."
                    : "Nothing ran: this server has no command named '" + label + "'. " + sender
                            + " is not short of a permission for it either.";
        };
    }
}
