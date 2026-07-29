package moe.vitamin.minecraft.mcp.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import moe.vitamin.minecraft.mcp.contract.Sequenced;

/**
 * Fixed-capacity, lock-free ring buffer that overwrites its oldest records.
 *
 * <p>This sits directly under the MONITOR listeners, so an append happens on the server's main
 * thread while a tick is in flight. It must never block: a lock here would let an HTTP request
 * stall the server. Appending is one atomic increment plus one array store.
 *
 * <p>Overwriting rather than blocking or growing is the deliberate choice. A server producing
 * events faster than anyone reads them is normal, and neither of the alternatives is
 * acceptable — blocking stalls the tick, growing runs the server out of memory. Losing the
 * oldest records is the only option that keeps the server healthy, so the design instead makes
 * sure the loss is always *reported*: every read returns how many records it could not see, and
 * that count travels all the way out to the caller.
 *
 * <p><b>Concurrency.</b> Any number of threads may append (Bukkit fires some events
 * asynchronously) and read concurrently. A reader can race an append that overwrites the slot
 * it is reading; that is detected by checking the record's own sequence against the one being
 * asked for, and a mismatch is counted as a drop rather than returned as bogus data.
 *
 * @param <T> the record type, which carries its own sequence
 */
public final class SequencedRingBuffer<T extends Sequenced> {

    /** Default capacity, per docs/roadmap.md Stage 1b. */
    public static final int DEFAULT_CAPACITY = 100_000;

    /** Largest power of two an array can be sized to here. */
    public static final int MAX_CAPACITY = 1 << 30;

    private final AtomicReferenceArray<T> slots;
    private final int capacity;
    private final int mask;

    /** Total records ever appended. The next append takes this value as its sequence. */
    private final AtomicLong writeSequence = new AtomicLong(0);

    /** Records a reader asked for but found already overwritten. */
    private final AtomicLong lostToRaces = new AtomicLong(0);

    /**
     * @param requestedCapacity minimum number of records to retain; rounded up to a power of
     *                          two so that index arithmetic is a mask instead of a modulo
     */
    public SequencedRingBuffer(int requestedCapacity) {
        if (requestedCapacity < 1) {
            throw new IllegalArgumentException(
                    "capacity must be at least 1 but was: " + requestedCapacity);
        }
        if (requestedCapacity > MAX_CAPACITY) {
            throw new IllegalArgumentException(
                    "capacity must not exceed " + MAX_CAPACITY + " but was: " + requestedCapacity);
        }
        int rounded = 1;
        while (rounded < requestedCapacity) {
            rounded <<= 1;
        }
        this.capacity = rounded;
        this.mask = rounded - 1;
        this.slots = new AtomicReferenceArray<>(rounded);
    }

    /**
     * Appends one record.
     *
     * <p>The sequence is claimed first and handed to {@code factory}, so the record is built
     * already knowing its own position. Splitting this into "claim, then publish" would leave a
     * window in which two threads could interleave and store records under each other's
     * sequence.
     *
     * @param factory builds the record from the sequence it was given
     * @return the sequence the record was stored under
     */
    public long append(LongFunction<? extends T> factory) {
        Objects.requireNonNull(factory, "factory");
        long sequence = writeSequence.getAndIncrement();
        slots.set((int) (sequence & mask), factory.apply(sequence));
        return sequence;
    }

    /**
     * Reads forward from {@code from}, up to {@code limit} matching records.
     *
     * <p>{@code limit} counts records that pass {@code filter}, so a selective filter still
     * fills a page instead of returning a handful of matches from the first {@code limit} slots.
     *
     * @param from  sequence to resume at, inclusive
     * @param limit maximum records to return
     * @param filter which records to include; {@code null} accepts everything
     */
    public Batch<T> read(long from, int limit, Predicate<? super T> filter) {
        if (from < 0) {
            throw new IllegalArgumentException("from must not be negative but was: " + from);
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1 but was: " + limit);
        }

        long write = writeSequence.get();
        long oldestRetained = Math.max(0, write - capacity);

        // Anything the caller asked for that has already been overwritten is gone for good.
        long dropped = Math.max(0, oldestRetained - from);
        long sequence = Math.max(from, oldestRetained);

        List<T> items = new ArrayList<>(Math.min(limit, 64));
        while (sequence < write && items.size() < limit) {
            T record = slots.get((int) (sequence & mask));
            if (record == null || record.sequence() != sequence) {
                // Overwritten by an append that landed while this read was walking the buffer,
                // or claimed but not yet stored. Either way it cannot be returned.
                dropped++;
                lostToRaces.incrementAndGet();
            } else if (filter == null || filter.test(record)) {
                items.add(record);
            }
            sequence++;
        }

        return new Batch<>(items, sequence, dropped, sequence >= write);
    }

    /**
     * Visits every retained record from {@code from} onward, without materialising them.
     *
     * <p>Used by {@code events_summary}, which has to touch the whole buffer to count by type.
     * Building a list of 100k records first would allocate megabytes per call to produce a
     * response of a few dozen lines.
     *
     * @return records in the requested range that were already overwritten
     */
    public long forEachRetained(long from, java.util.function.Consumer<? super T> action) {
        if (from < 0) {
            throw new IllegalArgumentException("from must not be negative but was: " + from);
        }
        Objects.requireNonNull(action, "action");

        long write = writeSequence.get();
        long oldestRetained = Math.max(0, write - capacity);
        long dropped = Math.max(0, oldestRetained - from);

        for (long sequence = Math.max(from, oldestRetained); sequence < write; sequence++) {
            T record = slots.get((int) (sequence & mask));
            if (record == null || record.sequence() != sequence) {
                dropped++;
            } else {
                action.accept(record);
            }
        }
        return dropped;
    }

    /** Records currently retained. */
    public int capacity() {
        return capacity;
    }

    /** Total records ever appended, across the whole lifetime of the buffer. */
    public long written() {
        return writeSequence.get();
    }

    /** Sequence of the oldest record still retained. */
    public long oldestRetainedSequence() {
        return Math.max(0, writeSequence.get() - capacity);
    }

    /**
     * Total records overwritten before anyone read them.
     *
     * <p>Reported by {@code server_info} so that a caller can tell "the server is quiet" from
     * "the buffer is being lapped and you are seeing a fraction of what happened".
     */
    public long overwritten() {
        return oldestRetainedSequence() + lostToRaces.get();
    }

    /**
     * One page of records read out of the buffer.
     *
     * @param items        matching records, oldest first
     * @param nextSequence sequence to resume from on the next read
     * @param dropped      records in the requested range that were already overwritten
     * @param exhausted    whether the read reached the newest record currently present
     */
    public record Batch<T>(List<T> items, long nextSequence, long dropped, boolean exhausted) {
        public Batch {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }
}
