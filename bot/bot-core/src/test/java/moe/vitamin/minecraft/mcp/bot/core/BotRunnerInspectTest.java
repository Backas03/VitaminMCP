package moe.vitamin.minecraft.mcp.bot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import moe.vitamin.minecraft.mcp.bot.spi.BossBar;
import moe.vitamin.minecraft.mcp.bot.spi.ClientMessage;
import moe.vitamin.minecraft.mcp.bot.spi.ClientView;
import org.junit.jupiter.api.Test;

class BotRunnerInspectTest {

    @Test
    void parsesTimestampedMessageRecordsAndNextCursor() {
        String messages = String.join(
                String.valueOf(RunnerProtocol.RECORD_SEPARATOR),
                message(7L, 1_725_000_000_100L, "first reply"),
                message(8L, 1_725_000_000_500L, "second reply"));
        String[] reply = reply(messages, 9L, "bot-42-3f86c68d");

        ClientView view = BotRunner.parseInspect(reply);

        assertEquals(17, reply.length);
        assertEquals(
                List.of(
                        new ClientMessage(7L, 1_725_000_000_100L, "first reply"),
                        new ClientMessage(8L, 1_725_000_000_500L, "second reply")),
                view.messages());
        assertEquals(9L, view.nextMessageSequence());
        assertEquals("bot-42-3f86c68d", view.messageStreamId());
    }

    @Test
    void parsesAnEmptyMessageFieldAndItsNextCursor() {
        ClientView view = BotRunner.parseInspect(reply("", 42L, "bot-42-3f86c68d"));

        assertEquals(List.of(), view.messages());
        assertEquals(42L, view.nextMessageSequence());
        assertEquals("bot-42-3f86c68d", view.messageStreamId());
    }

    @Test
    void compatibilityConstructorDerivesTheNextMessageSequence() {
        List<ClientMessage> messages = List.of(
                new ClientMessage(7L, 1_725_000_000_100L, "first reply"),
                new ClientMessage(8L, 1_725_000_000_500L, "second reply"));

        ClientView populated = new ClientView(
                null, List.of(), messages, List.<BossBar>of(), null);
        ClientView empty = new ClientView(
                null, List.of(), List.of(), List.<BossBar>of(), null);

        assertEquals(9L, populated.nextMessageSequence());
        assertEquals(0L, empty.nextMessageSequence());
        assertEquals("legacy", populated.messageStreamId());
        assertEquals("legacy", empty.messageStreamId());
    }

    private static String message(long sequence, long timestamp, String text) {
        return String.join(
                String.valueOf(RunnerProtocol.UNIT_SEPARATOR),
                String.valueOf(sequence), String.valueOf(timestamp), text);
    }

    private static String[] reply(
            String messages, long nextMessageSequence, String messageStreamId) {
        return RunnerProtocol.decode(RunnerProtocol.encode(
                RunnerProtocol.OK,
                RunnerProtocol.INSPECT,
                "-1",
                "",
                "",
                messages,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                String.valueOf(nextMessageSequence),
                messageStreamId));
    }
}
