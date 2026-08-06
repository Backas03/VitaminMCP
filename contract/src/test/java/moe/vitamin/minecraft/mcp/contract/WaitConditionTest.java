package moe.vitamin.minecraft.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WaitConditionTest {

    private static WaitCondition with(String key, Object value) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(key, value);
        return new WaitCondition("ticks", parameters);
    }

    /** The bug this pins down made a wait stop waiting. */
    @Test
    void aQuotedNumberIsStillANumber() {
        assertEquals(20, with("count", "20").integer("count", 1));
        assertEquals(20, with("count", 20).integer("count", 1));
        assertEquals(2.5, with("distance", "2.5").decimal("distance", 1.0));
        assertEquals(2.5, with("distance", 2.5).decimal("distance", 1.0));
    }

    @Test
    void surroundingSpaceDoesNotBreakIt() {
        assertEquals(7, with("count", " 7 ").integer("count", 1));
    }

    @Test
    void anAbsentKeyTakesTheDefault() {
        assertEquals(1, new WaitCondition("ticks", Map.of()).integer("count", 1));
        assertEquals(1.0, new WaitCondition("ticks", Map.of()).decimal("distance", 1.0));
    }

    /**
     * Present but unusable is refused rather than defaulted — the same mistake in the other
     * direction.
     */
    @Test
    void aValueThatIsNotANumberIsRefused() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> with("count", "twenty").integer("count", 1));
        assertTrue(thrown.getMessage().contains("count"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("twenty"), thrown.getMessage());
    }

    @Test
    void quotedBooleansAndAbsenceStayDistinguishable() {
        assertTrue(with("op", "true").bool("op", false));
        assertFalse(with("op", "false").bool("op", true));
        assertTrue(with("op", true).bool("op", false));

        assertTrue(with("op", false).has("op"));
        assertFalse(new WaitCondition("ticks", Map.of()).has("op"));
    }
}
