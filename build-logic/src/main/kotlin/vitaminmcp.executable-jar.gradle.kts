import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("vitaminmcp.java-conventions")
    id("com.gradleup.shadow")
}

/**
 * A self-contained jar that something launches with `java -jar`.
 *
 * Separate from `vitaminmcp.shadow-conventions`, which is for jars loaded *into* a Minecraft
 * server and therefore relocates everything they bundle. A process of our own has a classpath
 * to itself and nothing to collide with, so relocating there would be cost without benefit —
 * and would make stack traces from it harder to read for no reason.
 *
 * Set `mainClass` in the module applying this.
 */
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier = ""
    archiveVersion = ""
    mergeServiceFiles()

    // Bundled module descriptors name modules whose classes are now inside another jar.
    exclude("module-info.class")
    exclude("META-INF/versions/*/module-info.class")

    // Signatures of the jars that were merged in no longer describe the result, and a JVM that
    // checks them refuses to start rather than warning.
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}
