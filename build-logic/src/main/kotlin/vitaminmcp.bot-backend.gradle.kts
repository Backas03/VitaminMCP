import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

import java.util.zip.ZipFile

plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.executable-jar")
    id("vitaminmcp.module-rules")
}

/** One protocol version's bot implementation, built to be embedded in the runner bundle. */

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

        java.exclude { element ->
            !element.isDirectory
                    && element.file.startsWith(sharedJava)
                    && File(localJava, element.path).exists()
        }
    }
    named("test") {

        java.srcDir(sharedTestJava)
    }
}

dependencies {
    "implementation"(project(":contract"))
    "implementation"(project(":bot-core"))
}

/** Says which protocol this jar speaks, from the one place that cannot be wrong: its own name. */
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

    doLast {
        val implementation = "moe/vitamin/minecraft/mcp/bot/runner/Backend.class"
        val jar = archiveFile.get().asFile
        val present = ZipFile(jar).use { zip -> zip.getEntry(implementation) != null }
        if (!present) {
            throw GradleException(
                "${jar.name} does not contain $implementation. The shared backend source did " +
                    "not reach this module's compile — check the source-set wiring in " +
                    "vitaminmcp.bot-backend."
            )
        }
    }
}

tasks.test {
    listOf("vitaminmcp.liveServer", "vitaminmcp.host", "vitaminmcp.port",
           "vitaminmcp.token", "vitaminmcp.mcpPort", "vitaminmcp.repeat",
           "vitaminmcp.debugHandshake").forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
    testLogging { showStandardStreams = true }
}
