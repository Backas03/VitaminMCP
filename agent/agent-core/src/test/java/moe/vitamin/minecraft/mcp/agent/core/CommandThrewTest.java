package moe.vitamin.minecraft.mcp.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.command.CommandException;
import org.junit.jupiter.api.Test;

class CommandThrewTest {

    @Test
    void aThrowingCommandIsExplainedByItsRootCauseAndCanBeLookedUp() {
        ExceptionRegistry exceptions = new ExceptionRegistry();
        CommandException thrown = new CommandException(
                "Unhandled exception executing 'machineadmin view' in SigCommandAdapter(machineadmin)",
                new RuntimeException("Failed to execute command machineadmin view",
                        new NullPointerException("\"guiListData\" is null")));

        String reason = CaptureService.threw(exceptions, thrown);

        assertTrue(reason.startsWith("The command threw before it finished: "
                + "NullPointerException: \"guiListData\" is null."), reason);
        Matcher hash = Pattern.compile("hash '([0-9a-f]{16})'").matcher(reason);
        assertTrue(hash.find(), reason);
        assertNotNull(exceptions.byHash(hash.group(1)));
        assertEquals(CommandException.class.getName(), exceptions.byHash(hash.group(1)).type());
    }
}
