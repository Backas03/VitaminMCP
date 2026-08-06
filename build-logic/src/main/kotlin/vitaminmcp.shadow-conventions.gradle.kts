import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("vitaminmcp.java-conventions")
    id("com.gradleup.shadow")
}

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

    exclude("module-info.class")
    exclude("META-INF/versions/*/module-info.class")
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}
