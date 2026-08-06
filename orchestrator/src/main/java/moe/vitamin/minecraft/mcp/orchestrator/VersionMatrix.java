package moe.vitamin.minecraft.mcp.orchestrator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The versions to test against, read from {@code versions.yaml}.
 *
 * <p>Configuration rather than code, so that adding a version is one block and never a code
 * change (CONTRIBUTING.md invariant 7). Every entry is launched natively; there is no Via path any
 * more, which is why an entry no longer has to declare whether it is translated
 * (docs/design.md §4).
 *
 * <pre>
 * versions:
 *   - id: "1.21.8"
 *     paper: { version: "1.21.8", build: 60 }
 *   - id: "1.21.11"
 *     paper: { version: "1.21.11" }
 * </pre>
 *
 * <p>Parsed by hand. The file is a list of two-field entries, and orchestrator has no other
 * reason to carry a YAML library — one whose transitive versions would then have to be kept
 * clear of the ones a Minecraft server already ships.
 */
public record VersionMatrix(List<Entry> versions) {

    public VersionMatrix {
        versions = List.copyOf(Objects.requireNonNull(versions, "versions"));
    }

    /**
     * One version to test.
     *
     * @param id           name used in reports; usually the Minecraft version
     * @param paperVersion Minecraft version PaperMC publishes builds for
     * @param build        specific build, or {@code 0} for the latest
     */
    public record Entry(String id, String paperVersion, int build) {
        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(paperVersion, "paperVersion");
        }
    }

    public static VersionMatrix load(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Parses a matrix from YAML text.
     *
     * <p>Public alongside {@link #load(Path)} so a caller can build a matrix without a file —
     * a test asserting how an unreachable version is reported should not have to write one to
     * disk to do it.
     */
    public static VersionMatrix parse(String yaml) {
        List<Entry> entries = new ArrayList<>();
        String id = null;
        String version = null;
        int build = 0;

        for (String raw : yaml.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            if (line.startsWith("- ")) {
                // A new list item ends the previous entry, so entries are flushed on the way in
                // rather than needing a sentinel at the end of the file.
                if (id != null) {
                    entries.add(new Entry(id, version == null ? id : version, build));
                }
                id = null;
                version = null;
                build = 0;
                line = line.substring(2).strip();
            }

            if (line.startsWith("id:")) {
                id = unquote(line.substring(3));
            } else if (line.startsWith("paper:")) {
                String inline = line.substring(6).strip();
                if (inline.startsWith("{")) {
                    version = field(inline, "version");
                    String buildText = field(inline, "build");
                    build = buildText == null ? 0 : Integer.parseInt(buildText);
                }
            } else if (line.startsWith("version:")) {
                version = unquote(line.substring(8));
            } else if (line.startsWith("build:")) {
                build = Integer.parseInt(unquote(line.substring(6)));
            }
        }
        if (id != null) {
            entries.add(new Entry(id, version == null ? id : version, build));
        }

        if (entries.isEmpty()) {
            throw new IllegalArgumentException(
                    "versions.yaml lists no versions. Each entry needs at least an 'id'.");
        }
        return new VersionMatrix(entries);
    }

    /** Reads {@code key} out of an inline {@code {a: b, c: d}} mapping. */
    private static String field(String inline, String key) {
        for (String part : inline.replace("{", "").replace("}", "").split(",")) {
            String[] pair = part.split(":", 2);
            if (pair.length == 2 && pair[0].strip().equals(key)) {
                return unquote(pair[1]);
            }
        }
        return null;
    }

    private static String unquote(String value) {
        String trimmed = value.strip();
        if (trimmed.length() >= 2
                && (trimmed.startsWith("\"") && trimmed.endsWith("\"")
                        || trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
