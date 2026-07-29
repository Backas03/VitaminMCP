package moe.vitamin.minecraft.mcp.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import moe.vitamin.minecraft.mcp.contract.Sequenced;
import org.junit.jupiter.api.Test;

class SequencedRingBufferTest {

    /** Minimal stand-in so the buffer is exercised without dragging Bukkit in. */
    private record Item(long sequence, String value) implements Sequenced {}

    private static SequencedRingBuffer<Item> bufferOf(int capacity) {
        return new SequencedRingBuffer<>(capacity);
    }

    private static long appendValue(SequencedRingBuffer<Item> buffer, String value) {
        return buffer.append(sequence -> new Item(sequence, value));
    }

    @Test
    void readsBackWhatWasAppended() {
        SequencedRingBuffer<Item> buffer = bufferOf(8);
        appendValue(buffer, "a");
        appendValue(buffer, "b");

        SequencedRingBuffer.Batch<Item> batch = buffer.read(0, 10, null);

        assertEquals(List.of("a", "b"), batch.items().stream().map(Item::value).toList());
        assertEquals(0L, batch.dropped());
        assertTrue(batch.exhausted());
        assertEquals(2L, batch.nextSequence());
    }

    @Test
    void assignsConsecutiveSequencesStartingAtZero() {
        SequencedRingBuffer<Item> buffer = bufferOf(8);

        assertEquals(0L, appendValue(buffer, "a"));
        assertEquals(1L, appendValue(buffer, "b"));
        assertEquals(2L, appendValue(buffer, "c"));
    }

    @Test
    void roundsCapacityUpToAPowerOfTwo() {
        assertEquals(1, bufferOf(1).capacity());
        assertEquals(4, bufferOf(3).capacity());
        assertEquals(4, bufferOf(4).capacity());
        assertEquals(131_072, bufferOf(100_000).capacity());
    }

    @Test
    void overwritesTheOldestRecordsAndReportsTheLoss() {
        SequencedRingBuffer<Item> buffer = bufferOf(4);
        for (int i = 0; i < 10; i++) {
            appendValue(buffer, "v" + i);
        }

        // Only the last 4 survive; a reader that asked from the start must be told so.
        SequencedRingBuffer.Batch<Item> batch = buffer.read(0, 100, null);

        assertEquals(List.of("v6", "v7", "v8", "v9"), batch.items().stream().map(Item::value).toList());
        assertEquals(6L, batch.dropped());
        assertEquals(10L, batch.nextSequence());
    }

    @Test
    void aCursorInsideTheRetainedRangeLosesNothing() {
        SequencedRingBuffer<Item> buffer = bufferOf(4);
        for (int i = 0; i < 6; i++) {
            appendValue(buffer, "v" + i);
        }

        SequencedRingBuffer.Batch<Item> batch = buffer.read(4, 100, null);

        assertEquals(List.of("v4", "v5"), batch.items().stream().map(Item::value).toList());
        assertEquals(0L, batch.dropped());
    }

    @Test
    void limitCountsMatchesNotSlotsScanned() {
        SequencedRingBuffer<Item> buffer = bufferOf(64);
        for (int i = 0; i < 40; i++) {
            appendValue(buffer, i % 10 == 0 ? "hit" : "miss");
        }

        SequencedRingBuffer.Batch<Item> batch = buffer.read(0, 3, item -> item.value().equals("hit"));

        // A selective filter must still fill the page rather than returning whatever happened
        // to match within the first three slots.
        assertEquals(3, batch.items().size());
        assertFalse(batch.exhausted());
    }

    @Test
    void resumingFromTheReturnedSequenceNeitherRepeatsNorSkips() {
        SequencedRingBuffer<Item> buffer = bufferOf(64);
        for (int i = 0; i < 25; i++) {
            appendValue(buffer, "v" + i);
        }

        List<String> collected = new ArrayList<>();
        long cursor = 0;
        SequencedRingBuffer.Batch<Item> batch;
        do {
            batch = buffer.read(cursor, 7, null);
            batch.items().forEach(item -> collected.add(item.value()));
            cursor = batch.nextSequence();
        } while (!batch.exhausted());

        assertEquals(IntStream.range(0, 25).mapToObj(i -> "v" + i).toList(), collected);
    }

    @Test
    void aFirstReadFromZeroSurfacesEverythingAlreadyLost() {
        SequencedRingBuffer<Item> buffer = bufferOf(4);
        for (int i = 0; i < 100; i++) {
            appendValue(buffer, "v" + i);
        }

        // This is how a cursor-less query reaches the buffer. Reading from 0 rather than from
        // oldestRetainedSequence() is what makes the loss visible: starting at the oldest
        // retained record would report nothing dropped, on exactly the request where the caller
        // most needs to know it is seeing 4 records out of 100.
        SequencedRingBuffer.Batch<Item> batch = buffer.read(0, 10, null);

        assertEquals(96L, batch.dropped());
        assertEquals(4, batch.items().size());
    }

    @Test
    void reportsHowMuchHasBeenOverwritten() {
        SequencedRingBuffer<Item> buffer = bufferOf(4);
        for (int i = 0; i < 10; i++) {
            appendValue(buffer, "v" + i);
        }

        assertEquals(10L, buffer.written());
        assertEquals(6L, buffer.oldestRetainedSequence());
        assertEquals(6L, buffer.overwritten());
    }

    @Test
    void rejectsInvalidConstructionAndReads() {
        assertThrows(IllegalArgumentException.class, () -> bufferOf(0));
        assertThrows(IllegalArgumentException.class, () -> bufferOf(-1));

        SequencedRingBuffer<Item> buffer = bufferOf(8);
        assertThrows(IllegalArgumentException.class, () -> buffer.read(-1, 10, null));
        assertThrows(IllegalArgumentException.class, () -> buffer.read(0, 0, null));
    }

    @Test
    void concurrentAppendsClaimEverySequenceExactlyOnce() throws Exception {
        int threads = 8;
        int perThread = 4_000;
        int total = threads * perThread;

        // Capacity large enough that nothing is overwritten, so every sequence must be present.
        SequencedRingBuffer<Item> buffer = bufferOf(total * 2);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        appendValue(buffer, "x");
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(total, buffer.written());

        SequencedRingBuffer.Batch<Item> batch = buffer.read(0, total, null);
        assertEquals(total, batch.items().size());
        assertEquals(0L, batch.dropped());

        // Sequences must be dense and strictly increasing — no gaps, no duplicates.
        long expected = 0;
        for (Item item : batch.items()) {
            assertEquals(expected++, item.sequence());
        }
    }
}
