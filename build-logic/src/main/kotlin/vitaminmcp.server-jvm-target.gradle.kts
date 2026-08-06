import moe.vitamin.build.SupportedVersions

plugins {
    id("vitaminmcp.java-conventions")
}

/** Bytecode target for anything a Minecraft server loads. */
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = SupportedVersions.javaRelease
}
