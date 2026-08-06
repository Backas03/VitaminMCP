package moe.vitamin.minecraft.mcp.bot.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/** The backends carried inside this jar, and where they are unpacked to run. */
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

    /** Unpacks the backend for {@code protocol} and returns the jar. */
    Path extract(int protocol) throws IOException {
        String module = index.getProperty(PROTOCOL_PREFIX + protocol);
        if (module == null) {
            throw new IOException("This runner has no backend for protocol " + protocol
                    + ". It carries " + protocols() + "."
                    + " Add a bot/backends/backend-" + protocol + " module and rebuild.");
        }

        Path directory = cacheDirectory().resolve(String.valueOf(protocol))
                .resolve(index.getProperty("hash." + protocol, "unversioned"));
        Files.createDirectories(directory);
        Path jar = directory.resolve(module + ".jar");

        if (Files.exists(jar)) {
            return jar;
        }

        Path partial = Files.createTempFile(directory, module, ".part");

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
