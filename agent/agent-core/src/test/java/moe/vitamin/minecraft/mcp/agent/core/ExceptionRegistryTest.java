package moe.vitamin.minecraft.mcp.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import moe.vitamin.minecraft.mcp.contract.ExceptionGroup;
import org.junit.jupiter.api.Test;

class ExceptionRegistryTest {

    /** Throws from a fixed line so repeated calls produce identical frames. */
    private static IllegalStateException thrownFromHere(String message) {
        return new IllegalStateException(message);
    }

    private static IllegalStateException thrownFromElsewhere(String message) {
        return new IllegalStateException(message);
    }

    @Test
    void foldsRepeatsOfTheSameExceptionIntoOneGroup() {
        ExceptionRegistry registry = new ExceptionRegistry();

        for (int i = 0; i < 342; i++) {
            registry.record(thrownFromHere("boom"), 1_000L + i);
        }

        List<ExceptionGroup> recent = registry.recent(10);
        assertEquals(1, recent.size());
        assertEquals(342L, recent.get(0).count());
        assertEquals(1_000L, recent.get(0).firstSeen());
        assertEquals(1_341L, recent.get(0).lastSeen());
    }

    @Test
    void identityIgnoresTheMessage() {
        ExceptionRegistry registry = new ExceptionRegistry();

        // The same bug reported with a player name baked into each message must not split into
        // one group per player — that is exactly what grouping exists to prevent. Thrown from
        // a single call site, because differing call sites are legitimately different groups.
        long timestamp = 1L;
        for (String player : List.of("Alice", "Bob", "Carol")) {
            registry.record(thrownFromHere("failed for " + player), timestamp++);
        }

        assertEquals(1, registry.size());
        assertEquals(3L, registry.recent(10).get(0).count());
    }

    @Test
    void differentThrowSitesAreDifferentGroups() {
        ExceptionRegistry registry = new ExceptionRegistry();

        registry.record(thrownFromHere("x"), 1L);
        registry.record(thrownFromElsewhere("x"), 2L);

        assertEquals(2, registry.size());
    }

    @Test
    void differentExceptionTypesAreDifferentGroups() {
        assertNotEquals(
                ExceptionRegistry.hashOf(new IllegalStateException("x")),
                ExceptionRegistry.hashOf(new IllegalArgumentException("x")));
    }

    @Test
    void theCauseChainIsPartOfTheIdentity() {
        Throwable withCause = new RuntimeException("outer", new java.io.IOException("inner"));
        Throwable withoutCause = new RuntimeException("outer");

        assertNotEquals(ExceptionRegistry.hashOf(withCause), ExceptionRegistry.hashOf(withoutCause));
    }

    @Test
    void survivesACyclicCauseChain() {
        // initCause refuses a throwable causing itself, but nothing stops a two-step cycle,
        // and walking one without a bound never returns. The timeout is the actual assertion:
        // a regression here hangs the server thread that was unlucky enough to log it.
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");
        first.initCause(second);
        second.initCause(first);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertNotNull(ExceptionRegistry.hashOf(first));

            ExceptionRegistry registry = new ExceptionRegistry();
            assertNotNull(registry.record(first, 1L));
        });
    }

    @Test
    void listingOmitsStackTracesAndLookupIncludesThem() {
        ExceptionRegistry registry = new ExceptionRegistry();
        String hash = registry.record(thrownFromHere("boom"), 1L);

        // Cheap by default so it is always safe to start here; the full trace costs a second
        // call that the caller has to actually want.
        assertNull(registry.recent(10).get(0).stackTrace());

        ExceptionGroup detailed = registry.byHash(hash);
        assertNotNull(detailed.stackTrace());
        assertTrue(detailed.stackTrace().contains("IllegalStateException"));
    }

    @Test
    void ordersMostRecentlySeenFirst() {
        ExceptionRegistry registry = new ExceptionRegistry();
        registry.record(thrownFromHere("a"), 100L);
        registry.record(thrownFromElsewhere("b"), 500L);

        List<ExceptionGroup> recent = registry.recent(10);
        assertEquals(500L, recent.get(0).lastSeen());
        assertEquals(100L, recent.get(1).lastSeen());
    }

    @Test
    void evictsTheLeastRecentlySeenWhenFull() {
        ExceptionRegistry registry = new ExceptionRegistry(2);

        registry.record(new RuntimeException("a") {}, 100L);
        registry.record(new RuntimeException("b") {}, 200L);
        registry.record(new RuntimeException("c") {}, 300L);

        assertEquals(2, registry.size());
        // The oldest is gone; the two most recent survive.
        assertTrue(registry.recent(10).stream().allMatch(group -> group.lastSeen() >= 200L));
    }

    @Test
    void unknownHashesAndNullThrowablesAreHandled() {
        ExceptionRegistry registry = new ExceptionRegistry();

        assertNull(registry.record(null, 1L));
        assertNull(registry.byHash("nope"));
        assertNull(registry.byHash(null));
    }

    @Test
    void rejectsInvalidLimits() {
        ExceptionRegistry registry = new ExceptionRegistry();

        assertThrows(IllegalArgumentException.class, () -> registry.recent(0));
        assertThrows(IllegalArgumentException.class, () -> new ExceptionRegistry(0));
    }
}
