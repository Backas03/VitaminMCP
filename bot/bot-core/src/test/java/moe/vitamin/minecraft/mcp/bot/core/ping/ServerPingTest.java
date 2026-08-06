package moe.vitamin.minecraft.mcp.bot.core.ping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/** Reading the protocol out of a status reply. */
class ServerPingTest {

    @Test
    void readsTheProtocolOutOfTheVersionObject() throws Exception {
        assertEquals(772, ServerPing.protocolOf("""
                {"version":{"name":"1.21.8","protocol":772},
                 "players":{"max":20,"online":0},
                 "description":{"text":"A Minecraft Server"}}
                """));
    }

    /** A server that mentions "protocol" elsewhere must not be read instead. */
    @Test
    void ignoresTheWordProtocolOutsideTheVersionObject() throws Exception {
        assertEquals(769, ServerPing.protocolOf("""
                {"description":{"text":"we speak \\"protocol\\": 999 here"},
                 "version":{"name":"1.21.4","protocol":769}}
                """));
    }

    @Test
    void saysSoWhenTheReplyCarriesNoProtocol() {
        IOException failure = assertThrows(IOException.class,
                () -> ServerPing.protocolOf("{\"description\":\"nothing useful\"}"));
        assertTrue(failure.getMessage().contains("no protocol number"), failure.getMessage());
    }
}
