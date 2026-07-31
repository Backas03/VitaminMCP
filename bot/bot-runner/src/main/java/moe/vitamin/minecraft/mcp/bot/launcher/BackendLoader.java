package moe.vitamin.minecraft.mcp.bot.launcher;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import moe.vitamin.minecraft.mcp.bot.spi.BotBackend;

/**
 * Loads one backend into a class loader of its own.
 *
 * <p>Every MCProtocolLib build occupies the same package names, so two of them cannot share a
 * class path. They can share a <em>process</em>, which is what this is: the backend jar is loaded
 * child-first, so its protocol library is reached through this loader and nowhere else.
 *
 * <p>{@link BotBackend} and its records are the exception and are delegated to the parent. They
 * have to be: if the backend loaded its own copy, the interface it implements would be a
 * different class from the one the launcher casts to, and the failure would be a
 * {@code ClassCastException} naming the same type twice.
 */
final class BackendLoader {

    /**
     * Packages the child must not load itself.
     *
     * <p>The JDK because two copies of it is not a thing, and the SPI because it is the contract
     * — see the class comment. Everything else, including the backend's own copy of bot-core, is
     * loaded from the backend jar.
     */
    private static final List<String> PARENT_FIRST = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "moe.vitamin.minecraft.mcp.bot.spi.");

    private BackendLoader() {}

    /**
     * @param jar the extracted backend
     * @return its {@link BotBackend}, ready for {@link BotBackend#start}
     */
    static BotBackend load(Path jar) throws IOException {
        URLClassLoader loader = new ChildFirstLoader(
                jar.toUri().toURL(), BackendLoader.class.getClassLoader());

        return ServiceLoader.load(BotBackend.class, loader)
                .findFirst()
                .orElseThrow(() -> new IOException(jar.getFileName()
                        + " declares no BotBackend service. A backend jar needs a"
                        + " META-INF/services entry naming its implementation."));
    }

    private static final class ChildFirstLoader extends URLClassLoader {

        ChildFirstLoader(URL jar, ClassLoader parent) {
            super(new URL[] {jar}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = parentFirst(name) ? super.loadClass(name, false) : childFirst(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private Class<?> childFirst(String name) throws ClassNotFoundException {
            try {
                return findClass(name);
            } catch (ClassNotFoundException notInBackend) {
                // The backend is shaded and should be self-contained, so this is a fallback
                // rather than the normal path — but a class the JDK does not own and the jar
                // forgot is better resolved than reported as missing.
                return super.loadClass(name, false);
            }
        }

        private static boolean parentFirst(String name) {
            return PARENT_FIRST.stream().anyMatch(name::startsWith);
        }
    }
}
