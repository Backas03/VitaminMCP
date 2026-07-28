package moe.vitamin.minecraft.mcp.contract;

import java.util.List;
import java.util.Objects;

/**
 * The response shape every paginated query tool returns.
 *
 * <p>{@code truncated} and {@code dropped} are separate on purpose, because they mean very
 * different things to whoever is reading the answer:
 *
 * <ul>
 *   <li>{@code truncated} — the agent held data back to stay inside the response budget.
 *       Nothing is lost; page again with {@code nextCursor}.
 *   <li>{@code dropped} — the ring buffer overflowed and records are gone for good. No amount
 *       of paging brings them back, and any conclusion drawn from this page may be built on
 *       an incomplete picture.
 * </ul>
 *
 * <p>Collapsing the two into one flag would let an LLM mistake permanent data loss for a
 * paging boundary, which is exactly the kind of silent wrongness this field exists to prevent.
 *
 * @param items      the records in this page, oldest first
 * @param nextCursor token to resume after the last item, or {@code null} when the stream is
 *                   exhausted
 * @param truncated  whether the response budget cut this page short
 * @param dropped    records lost to ring buffer overflow before this page was read
 */
public record Page<T>(List<T> items, String nextCursor, boolean truncated, long dropped) {

    public Page {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (dropped < 0) {
            throw new IllegalArgumentException("dropped must not be negative but was: " + dropped);
        }
    }

    /** A complete, empty page — nothing matched and nothing was lost. */
    public static <T> Page<T> empty() {
        return new Page<>(List.of(), null, false, 0L);
    }

    /** Whether more records remain after this page. */
    public boolean hasMore() {
        return nextCursor != null;
    }
}
