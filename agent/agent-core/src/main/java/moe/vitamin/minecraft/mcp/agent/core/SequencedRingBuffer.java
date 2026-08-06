package moe.vitamin.minecraft.mcp.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import moe.vitamin.minecraft.mcp.contract.Sequenced;

/** Fixed-capacity, lock-free ring buffer that overwrites its oldest records. */
public final class SequencedRingBuffer<T extends Sequenced> {

    /** Default capacity, per docs/roadmap.md Stage 1b. */
    public static final int DEFAULT_CAPACITY = 100_000;

    /** Largest power of two an array can be sized to here. */
    public static final int MAX_CAPACITY = 1 << 30;

    private final AtomicReferenceArray<T> slots;
    private final int capacity;
    private final int mask;

    /** Total records ever appended. */
    private final AtomicLong writeSequence = new AtomicLong(0);

    /** Records a reader asked for but found already overwritten. */
    private final AtomicLong lostToRaces = new AtomicLong(0);

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

    /** Appends one record. */
    public long append(LongFunction<? extends T> factory) {
        Objects.requireNonNull(factory, "factory");
        long sequence = writeSequence.getAndIncrement();
        slots.set((int) (sequence & mask), factory.apply(sequence));
        return sequence;
    }

    /** Reads forward from {@code from}, up to {@code limit} matching records. */
    public Batch<T> read(long from, int limit, Predicate<? super T> filter) {
        if (from < 0) {
            throw new IllegalArgumentException("from must not be negative but was: " + from);
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1 but was: " + limit);
        }

        long write = writeSequence.get();
        long oldestRetained = Math.max(0, write - capacity);

        long dropped = Math.max(0, oldestRetained - from);
        long sequence = Math.max(from, oldestRetained);

        List<T> items = new ArrayList<>(Math.min(limit, 64));
        while (sequence < write && items.size() < limit) {
            T record = slots.get((int) (sequence & mask));
            if (record == null || record.sequence() != sequence) {

                dropped++;
                lostToRaces.incrementAndGet();
            } else if (filter == null || filter.test(record)) {
                items.add(record);
            }
            sequence++;
        }

        return new Batch<>(items, sequence, dropped, sequence >= write);
    }

    /** Visits every retained record from {@code from} onward, without materialising them. */
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

    /** Total records overwritten before anyone read them. */
    public long overwritten() {
        return oldestRetainedSequence() + lostToRaces.get();
    }

    /** One page of records read out of the buffer. */
    public record Batch<T>(List<T> items, long nextSequence, long dropped, boolean exhausted) {
        public Batch {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }
}
