package moe.vitamin.minecraft.mcp.contract;

import java.util.Objects;

/**
 * What is at one block position, and which world answered.
 *
 * <p>The world is here because omitting it is the common case: a caller who does not name one
 * means "the main world", and a reply that echoes the empty argument back cannot confirm what it
 * read. A read tool that will not say what it read is one a debugger stops trusting — which is how
 * this record came to exist.
 */
public record BlockState(String world, int x, int y, int z, String block) {

    public BlockState {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(block, "block");
    }
}
