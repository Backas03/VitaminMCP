package moe.vitamin.minecraft.mcp.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import moe.vitamin.minecraft.mcp.agent.core.CommandRefusal.Standing;
import org.junit.jupiter.api.Test;

/**
 * The ways a command comes back {@code dispatched: false}, told apart.
 *
 * <p>The facts underneath came off a live Paper 1.21.8: {@code /list} as a non-op player is a
 * refusal, the same command once that player is op is dispatched, and
 * {@code /definitelynotacommand} is unknown either way.
 */
class CommandRefusalTest {

    private static final String LIST = "minecraft.command.list";

    @Test
    void labelIsTheCommandWordWithoutNamespaceOrCase() {
        assertEquals("list", CommandRefusal.label("list"));
        assertEquals("list", CommandRefusal.label("  List  "));
        assertEquals("tp", CommandRefusal.label("tp Tester1 0 64 0"));
        assertEquals("list", CommandRefusal.label("minecraft:list"));
    }

    @Test
    void aVanillaCommandFromANonOpReadsAsARefusalAndNamesTheNode() {
        String reason = refusal(false, LIST, Standing.LACKED).explain();

        assertTrue(reason.startsWith("Nothing ran:"), reason);
        assertTrue(reason.contains("refused"), reason);
        assertTrue(reason.contains("Tester1"), reason);
        assertTrue(reason.contains(LIST), reason);
    }

    @Test
    void aPluginCommandFromANonOpNamesThePluginsOwnNode() {
        String reason = refusal(true, "myplugin.shop", Standing.LACKED).explain();

        assertTrue(reason.contains("myplugin.shop"), reason);
        assertFalse(reason.contains("minecraft.command."), reason);
    }

    @Test
    void anUnknownCommandSaysSoRatherThanLookingLikeARefusal() {
        String fromOp = refusal(false, "minecraft.command.nope", Standing.HELD).explain();
        String fromConsole = new CommandRefusal(
                "nope", "CONSOLE", true, false, null, Standing.HELD).explain();

        assertTrue(fromOp.contains("no command named 'nope'"), fromOp);
        assertFalse(fromOp.contains("refused"), fromOp);

        assertTrue(fromConsole.contains("no command named 'nope'"), fromConsole);
        assertTrue(fromConsole.contains("console is allowed every command"), fromConsole);
    }

    /**
     * The trap this class exists for. A non-op is told "no" about any node nobody defined, so
     * asking about minecraft.command.nope would turn an unknown command into a refusal.
     */
    @Test
    void anUndefinedNodeIsNotEvidenceOfARefusal() {
        assertEquals(Standing.UNDEFINED, CommandRefusal.standing(false, false, false));
        assertEquals(Standing.HELD, CommandRefusal.standing(false, false, true));
        assertEquals(Standing.LACKED, CommandRefusal.standing(true, false, false));
        assertEquals(Standing.HELD, CommandRefusal.standing(true, true, false));

        String reason = refusal(false, "minecraft.command.nope", Standing.UNDEFINED).explain();
        assertFalse(reason.contains("was refused"), reason);
        assertTrue(reason.contains("most likely not a command"), reason);
        assertTrue(reason.contains("Run it as the console"), reason);
    }

    @Test
    void aKnownCommandTheSenderMayUseBlamesTheDispatcherRatherThanPermission() {
        String reason = refusal(true, "myplugin.shop", Standing.HELD).explain();

        assertTrue(reason.contains("the server knows 'shop'"), reason);
        assertFalse(reason.contains("refused"), reason);
    }

    @Test
    void everyOutcomeSaysNothingRanSoNoneReadsAsACommandThatDidNothing() {
        for (CommandRefusal refusal : new CommandRefusal[] {
                refusal(false, LIST, Standing.LACKED),
                refusal(false, "minecraft.command.nope", Standing.UNDEFINED),
                refusal(false, "minecraft.command.nope", Standing.HELD),
                refusal(true, null, Standing.OPEN),
                new CommandRefusal("nope", "CONSOLE", true, false, null, Standing.HELD)}) {
            assertTrue(refusal.explain().startsWith("Nothing ran:"), refusal.toString());
        }
    }

    private static CommandRefusal refusal(boolean known, String permission, Standing standing) {
        return new CommandRefusal(
                known ? "shop" : label(permission), "Tester1", false, known, permission, standing);
    }

    private static String label(String permission) {
        return permission == null ? "shop" : permission.substring(permission.lastIndexOf('.') + 1);
    }
}
