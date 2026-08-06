package moe.vitamin.minecraft.mcp.contract;

/** A record that carries its own position in the stream it belongs to. */
public interface Sequenced {

    /** Position in the owning stream. */
    long sequence();
}
