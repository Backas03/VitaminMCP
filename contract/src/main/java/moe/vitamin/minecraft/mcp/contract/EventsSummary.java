package moe.vitamin.minecraft.mcp.contract;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Per-type event counts over a time window. */
public record EventsSummary(long from, long to, long total, long dropped, List<TypeCount> counts) {

    public EventsSummary {
        counts = List.copyOf(Objects.requireNonNull(counts, "counts"));
    }

    /** How often one event type occurred. */
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
