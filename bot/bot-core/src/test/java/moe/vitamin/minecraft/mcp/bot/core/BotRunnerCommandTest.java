package moe.vitamin.minecraft.mcp.bot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class BotRunnerCommandTest {

    private static final Path JAVA_HOME = Path.of("C:/Program Files/Java/jdk-21");

    @Test
    void launchesWindowsSeaDirectly() {
        assertEquals(
                List.of(
                        absolute("C:/runners/bot-runner-win-x64.exe"),
                        "127.0.0.1",
                        "25565"),
                BotRunner.commandFor(
                        Path.of("C:/runners/bot-runner-win-x64.exe"),
                        JAVA_HOME,
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
                        JAVA_HOME,
                        "127.0.0.1",
                        25565));
    }

    @Test
    void keepsTheJavaJarLauncherPath() {
        assertEquals(
                List.of(
                        JAVA_HOME.resolve("bin").resolve("java").toString(),
                        "-jar",
                        absolute("C:/runners/bot-runner.jar"),
                        "127.0.0.1",
                        "25565"),
                BotRunner.commandFor(
                        Path.of("C:/runners/bot-runner.jar"),
                        JAVA_HOME,
                        "127.0.0.1",
                        25565));
    }

    private static String absolute(String path) {
        return Path.of(path).toAbsolutePath().toString();
    }
}
