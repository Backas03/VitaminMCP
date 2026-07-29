import moe.vitamin.build.SupportedVersions

plugins {
    id("vitaminmcp.java-conventions")
}

/**
 * Bytecode target for anything a Minecraft server loads.
 *
 * The server picks the JVM, not us. A plugin compiled to a newer class file version simply
 * does not load — the server reports UnsupportedClassVersionError and carries on without it.
 * That is not hypothetical; see the note in [SupportedVersions].
 *
 * Derived from the floor rather than written down, so the two cannot drift apart. It is
 * declared separately from the toolchain even when the numbers match, because they mean
 * different things: the toolchain is what compiles, this is what a *server* can load. Raise
 * the toolchain to 25 and this line is what keeps the agent loadable.
 *
 * `release` rather than `targetCompatibility` on purpose: it also restricts the *API* to that
 * Java version, so a call to something added later fails at compile time here instead of at
 * plugin load on someone's server.
 */
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = SupportedVersions.javaRelease
}
