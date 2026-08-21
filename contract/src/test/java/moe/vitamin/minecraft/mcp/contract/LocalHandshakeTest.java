package moe.vitamin.minecraft.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalHandshakeTest {

    @TempDir
    Path home;

    private String realHome;

    @BeforeEach
    void redirectHome() {

        // VITAMINMCP_HOME wins over user.home, and a test cannot unset an environment variable.
        // Skipping beats quietly writing into the developer's own handshake directory.
        assumeTrue(System.getenv("VITAMINMCP_HOME") == null,
                "VITAMINMCP_HOME is set, so this test would write outside its temporary home");

        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        if (realHome != null) {
            System.setProperty("user.home", realHome);
        }
    }

    @Test
    void roundTripsThroughTheFileItWrites() throws IOException {
        LocalHandshake written =
                new LocalHandshake("127.0.0.1", 25585, 25565, "sekrit", "1.5.0", "Paper 1.21.8");
        written.write();

        assertEquals(Optional.of(written), LocalHandshake.read(25585));
    }

    @Test
    void keepsOneFilePerPortSoAProxiedNetworkIsSeveral() throws IOException {
        new LocalHandshake("127.0.0.1", 25585, 25577, "lobby-token", "1.5.0", "").write();
        new LocalHandshake("127.0.0.1", 25586, 25577, "survival-token", "1.5.0", "").write();

        List<LocalHandshake> all = LocalHandshake.readAll();

        assertEquals(List.of(25585, 25586), all.stream().map(LocalHandshake::mcpPort).toList());
        assertEquals("survival-token", LocalHandshake.read(25586).orElseThrow().token());
    }

    @Test
    void readsNothingWhereNoAgentHasRun() {
        assertEquals(Optional.empty(), LocalHandshake.read(25585));
        assertEquals(List.of(), LocalHandshake.readAll());
    }

    @Test
    void treatsADamagedFileAsNoFileAtAll() throws IOException {
        Path file = LocalHandshake.fileFor(25585);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "mcp-port=not-a-number\ntoken=sekrit\n");

        // The caller falls back to being told the details, which is the path that always works.
        assertEquals(Optional.empty(), LocalHandshake.read(25585));
        assertEquals(List.of(), LocalHandshake.readAll());
    }

    @Test
    void removesTheFileSoAStaleOneNeverOutlivesTheEndpoint() throws IOException {
        new LocalHandshake("127.0.0.1", 25585, 25565, "sekrit", "1.5.0", "").write();

        LocalHandshake.remove(25585);

        assertFalse(Files.exists(LocalHandshake.fileFor(25585)));
        LocalHandshake.remove(25585);
    }

    @Test
    void refusesAHandshakeThatWouldTellAClientNothingUseful() {
        assertThrows(IllegalArgumentException.class,
                () -> new LocalHandshake("127.0.0.1", 25585, 25565, "", "1.5.0", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new LocalHandshake("127.0.0.1", 0, 25565, "sekrit", "1.5.0", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new LocalHandshake("127.0.0.1", 25585, 99999, "sekrit", "1.5.0", ""));
    }

    @Test
    void keepsTheTokenOutOfItsOwnDescription() {
        LocalHandshake handshake =
                new LocalHandshake("127.0.0.1", 25585, 25565, "sekrit", "1.5.0", "Paper 1.21.8");

        assertFalse(handshake.toString().contains("sekrit"));
        assertTrue(handshake.toString().contains("25585"));
    }
}
