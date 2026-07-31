package moe.vitamin.minecraft.mcp.bot.spi;

/**
 * Where a bot is, as plain numbers.
 *
 * <p>Plain on purpose. The protocol library's own vector type differs between versions — 1.21.2
 * reworked the position packet entirely — and every place that type reaches is a place a version
 * fork could spread to. Converting at the point the packet arrives keeps the fork inside the one
 * file that reads it (docs/multi-version.md §2.1).
 */
public record Position(double x, double y, double z) {

    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }
}
