package moe.vitamin.minecraft.mcp.bot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ForwardingHandshakeTest {

    private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    /** The field separator, referenced rather than written inline. */
    private static final String SEP = String.valueOf(ForwardingHandshake.SEPARATOR);

    @Test
    void producesTheFourFieldsTheBackendParses() {
        String field = ForwardingHandshake.addressField(
                "mc.example.com", "203.0.113.7", BotIdentity.of("Notch", NOTCH));

        String[] parts = ForwardingHandshake.parse(field);

        assertEquals(3, parts.length);
        assertEquals("mc.example.com", parts[0]);
        assertEquals("203.0.113.7", parts[1]);
        assertEquals("069a79f444e94726a5befca90e38aaf5", parts[2]);
    }

    @Test
    void theUuidFieldIsUndashed() {
        String[] parts = ForwardingHandshake.parse(ForwardingHandshake.addressField(
                "localhost", "127.0.0.1", BotIdentity.of("Notch", NOTCH)));

        assertEquals(32, parts[2].length());
        assertEquals(-1, parts[2].indexOf('-'));
    }

    @Test
    void skinPropertiesRideAlongInTheLastField() {
        String textures = "[{\"name\":\"textures\",\"value\":\"abc\",\"signature\":\"def\"}]";
        BotIdentity skinned = new BotIdentity("Notch", NOTCH, textures);

        String[] parts = ForwardingHandshake.parse(
                ForwardingHandshake.addressField("localhost", "127.0.0.1", skinned));

        assertEquals(4, parts.length);
        assertEquals(textures, parts[3]);
    }

    @Test
    void rejectsASeparatorSmuggledIntoHostOrIp() {
        BotIdentity identity = BotIdentity.of("Notch", NOTCH);

        assertThrows(IllegalArgumentException.class, () ->
                ForwardingHandshake.addressField("evil" + SEP + "host", "127.0.0.1", identity));
        assertThrows(IllegalArgumentException.class, () ->
                ForwardingHandshake.addressField("localhost", "127.0.0.1" + SEP + "spoof", identity));
    }

    @Test
    void parseKeepsEmptyTrailingFields() {

        assertEquals(4, ForwardingHandshake.parse("a" + SEP + "b" + SEP + "c" + SEP).length);
    }
}
