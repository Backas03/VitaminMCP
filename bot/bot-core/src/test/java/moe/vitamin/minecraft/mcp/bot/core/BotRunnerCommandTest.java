package moe.vitamin.minecraft.mcp.bot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class BotRunnerCommandTest {

    @Test
    void launchesWindowsSeaDirectly() {
        assertEquals(
                List.of(
                        absolute("C:/runners/bot-runner-win-x64.exe"),
                        "127.0.0.1",
                        "25565"),
                BotRunner.commandFor(
                        Path.of("C:/runners/bot-runner-win-x64.exe"),
                        "127.0.0.1",
                        25565));
    }

    @Test
    void launchesUnixSeaNamesDirectly() {
        assertEquals(
                List.of(
                        absolute("C:/runners/bot-runner-linux-x64"),
                        "127.0.0.1",
                        "25565"),
                BotRunner.commandFor(
                        Path.of("C:/runners/bot-runner-linux-x64"),
                        "127.0.0.1",
                        25565));
    }

    @Test
    void launchesTheNodeScriptThroughNode() {
        assertEquals(
                List.of(
                        "node.exe",
                        absolute("C:/runners/runner.mjs"),
                        "127.0.0.1",
                        "25565"),
                BotRunner.commandFor(
                        Path.of("C:/runners/runner.mjs"),
                        "127.0.0.1",
                        25565));
    }

    private static String absolute(String path) {
        return Path.of(path).toAbsolutePath().toString();
    }
}
