package moe.vitamin.minecraft.mcp.bot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class BotIdentityTest {

    @Test
    void theSameNameAlwaysProducesTheSameUuid() {

        assertEquals(BotIdentity.of("Tester1").uuid(), BotIdentity.of("Tester1").uuid());
    }

    @Test
    void differentNamesProduceDifferentUuids() {
        assertNotEquals(BotIdentity.of("Tester1").uuid(), BotIdentity.of("Tester2").uuid());
    }

    @Test
    void matchesTheUuidAServerComputesForAnOfflinePlayer() {

        UUID expected = UUID.nameUUIDFromBytes(
                "OfflinePlayer:Notch".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(expected, BotIdentity.offlineUuid("Notch"));
        assertEquals(3, expected.version());
    }

    @Test
    void anExplicitUuidIsKept() {
        UUID premium = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

        assertEquals(premium, BotIdentity.of("Notch", premium).uuid());
    }

    @Test
    void undashedFormIsWhatGoesOnTheWire() {
        BotIdentity identity =
                BotIdentity.of("Notch", UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"));

        assertEquals("069a79f444e94726a5befca90e38aaf5", identity.undashedUuid());
        assertEquals(32, identity.undashedUuid().length());
    }

    @Test
    void absentPropertiesBecomeAnEmptyArray() {

        assertEquals("[]", BotIdentity.of("Tester1").propertiesJson());
        assertEquals("[]", new BotIdentity("Tester1", UUID.randomUUID(), null).propertiesJson());
        assertEquals("[]", new BotIdentity("Tester1", UUID.randomUUID(), "  ").propertiesJson());
    }

    @Test
    void rejectsNamesTheProtocolCannotCarry() {
        assertThrows(IllegalArgumentException.class, () -> BotIdentity.of(""));
        assertThrows(IllegalArgumentException.class, () -> BotIdentity.of("   "));
        assertThrows(IllegalArgumentException.class, () -> BotIdentity.of("ThisNameIsFarTooLong"));
    }

    @Test
    void acceptsTheLongestLegalName() {
        assertTrue(BotIdentity.of("SixteenCharsXXXX").name().length() == 16);
    }
}
