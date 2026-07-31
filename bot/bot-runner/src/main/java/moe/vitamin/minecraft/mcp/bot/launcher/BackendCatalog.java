package moe.vitamin.minecraft.mcp.bot.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * The backends carried inside this jar, and where they are unpacked to run.
 *
 * <p>Each backend is a complete shaded jar sitting in the bundle as a resource — never on the
 * class path, because two of them together would be exactly the collision the bundle exists to
 * avoid. They are described by a generated index rather than discovered by scanning, so this
 * works the same whether the launcher is running from the jar or from a directory during a
 * build.
 */
final class BackendCatalog {

    private static final String INDEX = "/backends/index.properties";
    private static final String PROTOCOL_PREFIX = "protocol.";

    private final Properties index = new Properties();

    BackendCatalog() throws IOException {
        try (InputStream in = BackendCatalog.class.getResourceAsStream(INDEX)) {
            if (in == null) {
                throw new IOException("This runner carries no backends: " + INDEX
                        + " is missing. It was built without any, which cannot work.");
            }
            index.load(in);
        }
    }

    /** Protocols this bundle can speak, in order. */
    Set<Integer> protocols() {
        Set<Integer> protocols = new TreeSet<>();
        for (String key : index.stringPropertyNames()) {
            if (key.startsWith(PROTOCOL_PREFIX)) {
                protocols.add(Integer.parseInt(key.substring(PROTOCOL_PREFIX.length())));
            }
        }
        return protocols;
    }

    /**
     * Unpacks the backend for {@code protocol} and returns the jar.
     *
     * <p>Cached beside the Paper downloads, outside any working directory, so it survives
     * between runs — and keyed by the bundle's version, so a new build never runs against the
     * previous build's extracted backend.
     */
    Path extract(int protocol) throws IOException {
        String module = index.getProperty(PROTOCOL_PREFIX + protocol);
        if (module == null) {
            throw new IOException("This runner has no backend for protocol " + protocol
                    + ". It carries " + protocols() + "."
                    + " Add a bot/backends/backend-" + protocol + " module and rebuild.");
        }

        // Keyed on what the backend *is*, not on which build produced it. A version number does
        // not change while it is being worked on, so keying on one meant a rebuilt backend went
        // on running from the previously unpacked copy.
        Path directory = cacheDirectory().resolve(String.valueOf(protocol))
                .resolve(index.getProperty("hash." + protocol, "unversioned"));
        Files.createDirectories(directory);
        Path jar = directory.resolve(module + ".jar");

        if (Files.exists(jar)) {
            return jar;
        }

        // Written beside the target and moved into place, so a second runner starting at the
        // same moment either sees no file or a complete one, never a half-written jar.
        Path partial = Files.createTempFile(directory, module, ".part");
        // `.backend`, not `.jar`: shadow unzips anything inside the bundle that looks like an
        // archive, and the extension is what keeps this one whole. See bot-runner's build.
        try (InputStream in = BackendCatalog.class
                .getResourceAsStream("/backends/" + module + ".backend")) {
            if (in == null) {
                throw new IOException("The index names " + module
                        + " but this jar does not contain it.");
            }
            Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(partial, jar, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException alreadyThere) {
            Files.deleteIfExists(partial);
            if (!Files.exists(jar)) {
                throw alreadyThere;
            }
        }
        return jar;
    }

    /** Overridable for a machine whose home directory is not writable. */
    static Path cacheDirectory() {
        String configured = System.getProperty("vitaminmcp.backendCache");
        return configured != null && !configured.isBlank()
                ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), ".vitaminmcp", "backends");
    }
}
