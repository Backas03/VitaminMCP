package moe.vitamin.minecraft.mcp.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the one thing writing a generated token back to config.yml could quietly destroy.
 *
 * <p>The shipped config.yml is documentation — the README sends people to it to find out what
 * every setting does and why its default is what it is. `saveConfig()` rewrites the whole file, so
 * if Bukkit's YAML dropped comments on the way through, minting a token would silently delete all
 * of it on the very first start, on every install.
 *
 * <p>Bukkit keeps them, from 1.18 onwards. That is above this project's 1.21 floor, so it holds —
 * but it is a property of a library we do not own, on a path that runs once and never again.
 */
class ConfigCommentsTest {

    @TempDir
    Path directory;

    @Test
    void writingATokenBackKeepsTheDocumentationInConfigYml() throws IOException {
        Path file = directory.resolve("config.yml");
        Files.write(file, shippedConfig());

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file.toFile());
        config.set("auth-token", "a-generated-token");
        config.save(file.toFile());

        String saved = Files.readString(file);

        assertEquals("a-generated-token", YamlConfiguration
                .loadConfiguration(file.toFile()).getString("auth-token"));

        assertTrue(saved.contains("REQUIRED"),
                "The auth-token comment was dropped by saving; a first start would erase it");
        assertTrue(saved.contains("read-only"), "read-only went missing entirely");
        assertTrue(saved.contains("Read-only exposes only the query tools"),
                "The read-only comment was dropped by saving");
        assertTrue(saved.contains("local-handshake"), "local-handshake went missing entirely");
    }

    private static byte[] shippedConfig() throws IOException {
        try (InputStream in = Objects.requireNonNull(
                ConfigCommentsTest.class.getResourceAsStream("/config.yml"),
                "config.yml is not on the test classpath")) {
            return in.readAllBytes();
        }
    }
}
