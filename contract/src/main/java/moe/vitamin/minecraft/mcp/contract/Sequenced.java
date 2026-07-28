package moe.vitamin.minecraft.mcp.contract;

/**
 * A record that carries its own position in the stream it belongs to.
 *
 * <p>Holding the sequence on the record rather than beside it is what lets a reader tell a
 * live record from one that was overwritten underneath it: the slot it read is only valid if
 * the record found there still reports the sequence that was asked for.
 */
public interface Sequenced {

    /** Position in the owning stream. Never negative, and unique within that stream. */
    long sequence();
}
