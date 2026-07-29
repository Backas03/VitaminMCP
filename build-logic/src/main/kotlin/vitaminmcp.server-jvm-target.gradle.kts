plugins {
    id("vitaminmcp.java-conventions")
}

/**
 * Bytecode target for anything a Minecraft server loads.
 *
 * The server picks the JVM, not us. A plugin compiled to a newer class file version simply
 * does not load — the server reports UnsupportedClassVersionError and carries on without it,
 * which is how this constraint was found: Java 21 bytecode (class file 65) on Paper 1.13,
 * whose JVM reads up to 55.
 *
 * Minecraft's own requirements set the floor:
 *
 *     1.13 - 1.16.5   Java 8+
 *     1.17            Java 16+
 *     1.18 - 1.20.4   Java 17+
 *     1.20.5+         Java 21+
 *
 * The supported floor is 1.18 (docs/design.md §5), so 17 it is. Records, pattern matching for
 * instanceof, switch expressions and text blocks all survive; only Java 21's pattern matching
 * for switch does not.
 *
 * `release` rather than `targetCompatibility` on purpose: it also restricts the *API* to what
 * Java 17 shipped, so a call to something added in 18+ fails at compile time here instead of
 * at plugin load on someone's server.
 */
val serverJvmTarget = 17

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = serverJvmTarget
}
