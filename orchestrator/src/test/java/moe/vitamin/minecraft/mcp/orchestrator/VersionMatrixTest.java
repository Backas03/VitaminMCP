package moe.vitamin.minecraft.mcp.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class VersionMatrixTest {

    @Test
    void readsTheShippedShape() {
        VersionMatrix matrix = VersionMatrix.parse("""
                versions:
                  - id: "1.21.8"
                    paper: { version: "1.21.8", build: 60 }
                  - id: "1.21.11"
                    paper: { version: "1.21.11" }
                """);

        assertEquals(2, matrix.versions().size());
        assertEquals("1.21.8", matrix.versions().get(0).id());
        assertEquals(60, matrix.versions().get(0).build());

        assertEquals(0, matrix.versions().get(1).build());
    }

    @Test
    void acceptsTheBlockFormToo() {
        VersionMatrix matrix = VersionMatrix.parse("""
                versions:
                  - id: "1.21.8"
                    paper:
                      version: "1.21.8"
                      build: 60
                """);

        assertEquals("1.21.8", matrix.versions().get(0).paperVersion());
        assertEquals(60, matrix.versions().get(0).build());
    }

    @Test
    void paperVersionDefaultsToTheId() {
        VersionMatrix matrix = VersionMatrix.parse("""
                versions:
                  - id: "1.21.8"
                """);

        assertEquals("1.21.8", matrix.versions().get(0).paperVersion());
    }

    @Test
    void commentsAndBlankLinesAreIgnored() {
        VersionMatrix matrix = VersionMatrix.parse("""
                # the floor
                versions:

                  - id: "1.21.8"
                    paper: { version: "1.21.8" }
                """);

        assertEquals(1, matrix.versions().size());
        assertEquals("1.21.8", matrix.versions().get(0).id());
    }

    @Test
    void anEmptyMatrixIsRejected() {

        assertThrows(IllegalArgumentException.class, () -> VersionMatrix.parse("versions:\n"));
    }
}
