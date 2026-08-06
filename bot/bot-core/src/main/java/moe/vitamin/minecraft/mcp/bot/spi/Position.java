package moe.vitamin.minecraft.mcp.bot.spi;

/** Where a bot is, as plain numbers. */
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
