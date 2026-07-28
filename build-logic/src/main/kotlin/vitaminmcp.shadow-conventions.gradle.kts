import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("vitaminmcp.java-conventions")
    id("com.gradleup.shadow")
}

// CLAUDE.md invariant 4. These jars are loaded into a running server JVM, so anything they
// bundle collides with the server itself and with every other plugin. Netty is the dangerous
// one: the server is already using it, and a missed relocation surfaces as a crash with no
// obvious link back to this plugin.
val relocationRoot = "moe.vitamin.minecraft.mcp.libs"

val relocatedPackages = listOf(
    "io.netty",
    "com.fasterxml.jackson",
    "com.google.common",
    "com.google.gson",
    "io.github.classgraph",
    "nonapi.io.github.classgraph",
)

tasks.named<Jar>("jar") {
    archiveClassifier = "plain"
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier = ""
    relocatedPackages.forEach { relocate(it, "$relocationRoot.$it") }
    mergeServiceFiles()
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}
