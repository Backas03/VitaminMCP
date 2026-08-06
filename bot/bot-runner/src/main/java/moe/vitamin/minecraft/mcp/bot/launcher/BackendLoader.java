package moe.vitamin.minecraft.mcp.bot.launcher;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import moe.vitamin.minecraft.mcp.bot.spi.BotBackend;

/** Loads one backend into a class loader of its own. */
final class BackendLoader {

    /** Packages the child must not load itself. */
    private static final List<String> PARENT_FIRST = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "moe.vitamin.minecraft.mcp.bot.spi.");

    private BackendLoader() {}

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

                return super.loadClass(name, false);
            }
        }

        private static boolean parentFirst(String name) {
            return PARENT_FIRST.stream().anyMatch(name::startsWith);
        }
    }
}
