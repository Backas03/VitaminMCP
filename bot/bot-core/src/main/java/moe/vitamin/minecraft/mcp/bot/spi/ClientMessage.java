package moe.vitamin.minecraft.mcp.bot.spi;

import java.util.Objects;

/** One message the client received, and its position in that bot's message stream. */
public record ClientMessage(long sequence, long timestamp, String text) {

    public ClientMessage {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative: " + sequence);
        }
        if (timestamp < 0) {
            throw new IllegalArgumentException("timestamp must not be negative: " + timestamp);
        }
        text = Objects.requireNonNull(text, "text");
    }
}
