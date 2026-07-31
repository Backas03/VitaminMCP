import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.executable-jar")
    id("vitaminmcp.module-rules")
}

/**
 * One protocol version's bot implementation, built to be embedded in the runner bundle.
 *
 * A backend module is meant to be **a directory and a coordinate**: everything else — which
 * source it compiles, what its jar is called, how it announces itself — is derived here from the
 * module's own name. Adding a protocol should not require reading this file.
 *
 * The jar is self-contained and unrelocated, like anything else we launch ourselves. It is never
 * on a class path beside another backend; the launcher loads it through a class loader of its
 * own, which is the only reason several MCProtocolLib builds can ship in one artifact
 * (docs/multi-version.md §2.1).
 */

val protocol = project.name.removePrefix("backend-")

require(protocol.toIntOrNull() != null) {
    "A backend module must be named backend-<protocol>, e.g. backend-772. '${project.name}' is not."
}

val sharedJava = rootProject.layout.projectDirectory
    .dir("bot/backends/shared/src/main/java").asFile
val sharedResources = rootProject.layout.projectDirectory
    .dir("bot/backends/shared/src/main/resources").asFile
val sharedTestJava = rootProject.layout.projectDirectory
    .dir("bot/backends/shared/src/test/java").asFile
val localJava = layout.projectDirectory.dir("src/main/java").asFile

sourceSets {
    named("main") {
        java.srcDir(sharedJava)
        resources.srcDir(sharedResources)

        // Override is "drop a file at the same path": if this backend carries its own copy, the
        // shared one is left out of the compile.
        //
        // Expressed against the absolute path on purpose. `java.exclude("**/BotSession.java")`
        // would read as the same rule and is not — a pattern is matched inside *every* source
        // directory, so it would drop the backend's own copy as well and leave a
        // missing-symbol error that names neither file.
        java.exclude { element ->
            element.file.startsWith(sharedJava) && File(localJava, element.path).exists()
        }
    }
    named("test") {
        // The live smoke test is shared too, so every backend is held to the same one rather
        // than to whatever its author remembered to copy.
        java.srcDir(sharedTestJava)
    }
}

dependencies {
    "implementation"(project(":contract"))
    "implementation"(project(":bot-core"))
}

/**
 * Says which protocol this jar speaks, from the one place that cannot be wrong: its own name.
 *
 * The backend reports this at startup and the launcher checks it against what the server said.
 * A constant in the shared source could not vary per backend, and one written by hand in each
 * would be the first thing to go stale after a module was copied.
 */
val backendDescriptor = layout.buildDirectory.dir("generated/backend-descriptor")

val writeBackendDescriptor = tasks.register("writeBackendDescriptor") {
    description = "Records this backend's protocol number for the launcher to verify."
    val target = backendDescriptor.map { it.file("META-INF/vitaminmcp-backend.properties") }
    val value = protocol
    val module = project.name
    outputs.file(target)
    doLast {
        val file = target.get().asFile
        file.parentFile.mkdirs()
        file.writeText("protocol=$value\nmodule=$module\n")
    }
}

sourceSets.named("main") {
    resources.srcDir(files(backendDescriptor).builtBy(writeBackendDescriptor))
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName = project.name
}

// Live tests connect to a real server, so they stay off unless asked for. Every property has to
// appear here or it is silently ignored — a missing one once turned a 50-iteration flakiness run
// into 5 that reported success.
tasks.test {
    listOf("vitaminmcp.liveServer", "vitaminmcp.host", "vitaminmcp.port",
           "vitaminmcp.token", "vitaminmcp.mcpPort", "vitaminmcp.repeat",
           "vitaminmcp.debugHandshake").forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
    testLogging { showStandardStreams = true }
}
