package moe.vitamin.minecraft.mcp.contract;

import java.util.List;
import java.util.Objects;

/** The response shape every paginated query tool returns. */
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
