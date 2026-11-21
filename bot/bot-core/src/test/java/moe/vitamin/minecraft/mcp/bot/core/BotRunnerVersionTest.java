package moe.vitamin.minecraft.mcp.bot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BotRunnerVersionTest {

    @TempDir
    Path directory;

    @Test
    void aRunnerThatReportsNoVersionIsNotTrustedWithMicrosoftAuth() throws IOException {
        Path log = directory.resolve("received");
        try (BotRunner runner = BotRunner.launch(runner("ready\\t772", log), "127.0.0.1", 25565)) {
            assertNull(runner.version());

            IOException refused = assertThrows(IOException.class,
                    () -> runner.spawn("Backas", null, "microsoft", "my-ms-account"));

            assertTrue(refused.getMessage().contains("does not report its version"),
                    refused.getMessage());
            assertTrue(refused.getMessage().contains("unverified_username"), refused.getMessage());
        }
        assertFalse(Files.readString(log).contains("spawn"), "the spawn reached the runner");
    }

    @Test
    void aRunnerThatReportsItsVersionGetsTheMicrosoftSpawn() throws IOException {
        Path log = directory.resolve("received");
        try (BotRunner runner = BotRunner.launch(runner("ready\\t772\\t0\\t3.2.2", log),
                "127.0.0.1", 25565)) {
            assertEquals("3.2.2", runner.version());
            assertEquals(0, runner.pingProtocol());

            BotRunner.BotHandle bot = runner.spawn("Backas", null, "Microsoft", "my-ms-account");

            assertEquals("Backas", bot.playerName());
        }
        assertTrue(Files.readString(log).contains("spawn\tBackas\t\tmicrosoft\tmy-ms-account"));
    }

    @Test
    void anAuthNoRunnerUnderstandsIsRefusedBeforeItIsSent() throws IOException {
        Path log = directory.resolve("received");
        try (BotRunner runner = BotRunner.launch(runner("ready\\t772\\t0\\t3.2.2", log),
                "127.0.0.1", 25565)) {
            IOException refused = assertThrows(IOException.class,
                    () -> runner.spawn("Backas", null, "mojang", null));

            assertTrue(refused.getMessage().contains("Use offline or microsoft"), refused.getMessage());
        }
        assertFalse(Files.readString(log).contains("spawn"), "the spawn reached the runner");
    }

    private Path runner(String ready, Path log) throws IOException {
        Path script = directory.resolve("runner.mjs");
        Files.writeString(log, "");
        Files.writeString(script, """
                import { appendFileSync } from 'node:fs';
                import { createInterface } from 'node:readline';
                const log = %s;
                process.stdout.write('%s\\n');
                for await (const line of createInterface({ input: process.stdin })) {
                  appendFileSync(log, line + '\\n');
                  const [verb, name] = line.split('\\t');
                  if (verb === 'shutdown') break;
                  if (verb === 'spawn') {
                    process.stdout.write(['ok', 'spawn', '0.0', '64.0', '0.0', name,
                      'eb709cf6-1470-4a99-9f4d-cbe46a6089c0'].join('\\t') + '\\n');
                  } else {
                    process.stdout.write('ok\\t' + verb + '\\n');
                  }
                }
                """.formatted(quoted(log), ready));
        return script;
    }

    private static String quoted(Path path) {
        return "'" + path.toAbsolutePath().toString().replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
