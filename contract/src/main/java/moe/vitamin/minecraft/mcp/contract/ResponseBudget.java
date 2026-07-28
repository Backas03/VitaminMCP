package moe.vitamin.minecraft.mcp.contract;

/**
 * The hard ceiling every query tool answers within.
 *
 * <p>The limits live in contract rather than in each tool so that "no unbounded response" is a
 * property of the protocol instead of a habit each new tool has to remember. A tool that
 * returns more than this is a bug, not a configuration choice.
 *
 * <p>Both limits are needed. A count alone does not bound the response, because a single event
 * with a large payload can dwarf a hundred small ones; a byte limit alone gives no predictable
 * paging behaviour. Whichever is reached first ends the page.
 *
 * @param maxItems maximum records in one response
 * @param maxBytes maximum serialized size of one response
 */
public record ResponseBudget(int maxItems, int maxBytes) {

    /** Default ceiling: 200 records or 50 KB, whichever comes first. */
    public static final ResponseBudget DEFAULT = new ResponseBudget(200, 50 * 1024);

    public ResponseBudget {
        if (maxItems < 1) {
            throw new IllegalArgumentException("maxItems must be at least 1 but was: " + maxItems);
        }
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be at least 1 but was: " + maxBytes);
        }
    }

    /**
     * Clamps a caller-supplied limit into this budget.
     *
     * <p>A caller asking for more than the ceiling gets the ceiling rather than an error: the
     * request is reasonable, only the size is not, and failing it would just cost a round trip.
     */
    public int clampLimit(int requested) {
        return requested < 1 ? maxItems : Math.min(requested, maxItems);
    }
}
