package moe.vitamin.minecraft.mcp.bot.spi;

import java.util.List;
import java.util.Objects;

/** Everything the client knows that the server will not report. */
public record ClientView(
        OpenMenu menu,
        List<MenuItem> items,
        List<ClientMessage> messages,
        long nextMessageSequence,
        String messageStreamId,
        List<BossBar> bossBars,
        Scoreboard scoreboard,
        Float health,
        Integer food,
        Integer experienceLevel,
        Integer totalExperience,
        Float experienceProgress,
        List<String> effects) {

    /** Compatibility constructor for the Java runner, which predates Stage 6 client stats. */
    public ClientView(
            OpenMenu menu,
            List<MenuItem> items,
            List<ClientMessage> messages,
            List<BossBar> bossBars,
            Scoreboard scoreboard) {
        this(menu, items, messages, sequenceAfter(messages), "legacy", bossBars, scoreboard,
                null, null, null, null, null, List.of());
    }

    public ClientView {
        items = List.copyOf(items);
        messages = List.copyOf(messages);
        messageStreamId = Objects.requireNonNull(messageStreamId, "messageStreamId");
        if (nextMessageSequence < 0) {
            throw new IllegalArgumentException(
                    "nextMessageSequence must not be negative: " + nextMessageSequence);
        }
        if (messageStreamId.isBlank()) {
            throw new IllegalArgumentException("messageStreamId must not be blank");
        }
        validateMessageSequence(messages, nextMessageSequence);
        bossBars = List.copyOf(bossBars);
        effects = List.copyOf(effects);
    }

    private static long sequenceAfter(List<ClientMessage> messages) {
        return messages.isEmpty() ? 0L : messages.get(messages.size() - 1).sequence() + 1L;
    }

    private static void validateMessageSequence(
            List<ClientMessage> messages, long nextMessageSequence) {
        if (messages.isEmpty()) {
            return;
        }
        long expected = messages.get(0).sequence();
        for (ClientMessage message : messages) {
            if (message.sequence() != expected) {
                throw new IllegalArgumentException(
                        "message sequence gap: expected " + expected
                                + " but was " + message.sequence());
            }
            expected++;
        }
        if (expected != nextMessageSequence) {
            throw new IllegalArgumentException(
                    "nextMessageSequence must follow the last message: expected " + expected
                            + " but was " + nextMessageSequence);
        }
    }
}
