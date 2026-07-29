plugins {
    id("vitaminmcp.java-conventions")
}

/**
 * Bytecode target for anything a Minecraft server loads.
 *
 * The server picks the JVM, not us. A plugin compiled to a newer class file version simply
 * does not load — the server reports UnsupportedClassVersionError and carries on without it.
 * That is not hypothetical: it is how the original 1.13 floor was found to be impossible.
 *
 * Minecraft's own requirements set the floor:
 *
 *     1.13 - 1.16.5   Java 8+
 *     1.17            Java 16+
 *     1.18 - 1.20.4   Java 17+
 *     1.20.5+         Java 21+
 *
 * The supported floor is 1.21.8 (docs/design.md §5), which requires Java 21 — so this happens
 * to match the toolchain today. It is declared anyway, and separately, because the two are
 * different things: the toolchain is what compiles, this is what the *server* can load. If the
 * toolchain is ever raised to 25, this line is what keeps the agent loadable.
 *
 * `release` rather than `targetCompatibility` on purpose: it also restricts the *API* to what
 * Java 21 shipped, so a call to something added in 22+ fails at compile time here instead of
 * at plugin load on someone's server.
 */
val serverJvmTarget = 21

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = serverJvmTarget
}
