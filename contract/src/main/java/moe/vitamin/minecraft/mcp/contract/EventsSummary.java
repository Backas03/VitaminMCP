package moe.vitamin.minecraft.mcp.contract;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Per-type event counts over a time window.
 *
 * <p>This is the entry point for looking at events, and the reason the two-step shape exists
 * at all: a raw dump would spend the entire response budget on movement packets before
 * reaching anything worth reading. The summary is small no matter how busy the server is, and
 * it tells the caller which handful of types are worth querying in detail.
 *
 * @param from    epoch milliseconds of the window start, inclusive
 * @param to      epoch milliseconds of the window end, exclusive
 * @param total   events captured in the window across all types
 * @param dropped events lost to ring buffer overflow within the window
 * @param counts  per-type counts, most frequent first
 */
public record EventsSummary(long from, long to, long total, long dropped, List<TypeCount> counts) {

    public EventsSummary {
        counts = List.copyOf(Objects.requireNonNull(counts, "counts"));
    }

    /**
     * How often one event type occurred.
     *
     * @param type  simple class name, matching {@link EventRecord#type()}
     * @param count occurrences in the window
     */
    public record TypeCount(String type, long count) {
        public TypeCount {
            Objects.requireNonNull(type, "type");
        }

        /** Orders by count descending, then by type, so responses are stable. */
        public static Comparator<TypeCount> mostFrequentFirst() {
            return Comparator.comparingLong(TypeCount::count).reversed()
                    .thenComparing(TypeCount::type);
        }
    }
}
