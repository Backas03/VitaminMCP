package moe.vitamin.minecraft.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CursorTest {

    @Test
    void roundTripsThroughItsEncodedForm() {
        Cursor cursor = new Cursor(Cursor.EVENTS, 42L);

        assertEquals(cursor, Cursor.parse(cursor.encode(), Cursor.EVENTS));
    }

    @Test
    void survivesSequencesBeyondIntRange() {
        Cursor cursor = new Cursor(Cursor.LOGS, 9_000_000_000L);

        assertEquals(9_000_000_000L, Cursor.parse(cursor.encode(), Cursor.LOGS).sequence());
    }

    @Test
    void rejectsACursorFromAnotherStream() {
        String eventsCursor = new Cursor(Cursor.EVENTS, 42L).encode();

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> Cursor.parse(eventsCursor, Cursor.LOGS));

        // The whole point of putting the stream in the token: paging the wrong stream fails
        // loudly rather than returning plausible-looking but unrelated records.
        assertTrue(thrown.getMessage().contains(Cursor.EVENTS));
        assertTrue(thrown.getMessage().contains(Cursor.LOGS));
    }

    @Test
    void rejectsMalformedTokens() {
        assertThrows(IllegalArgumentException.class, () -> Cursor.parse("nonsense", Cursor.EVENTS));
        assertThrows(IllegalArgumentException.class, () -> Cursor.parse("events:notanumber", Cursor.EVENTS));
    }

    @Test
    void rejectsANegativeSequence() {
        assertThrows(IllegalArgumentException.class, () -> new Cursor(Cursor.EVENTS, -1L));
    }

    @Test
    void startsAtZero() {
        assertEquals(0L, Cursor.start(Cursor.EVENTS).sequence());
    }
}
