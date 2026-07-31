// Imported rather than written out: inside a build script `java` is the JavaPluginExtension, so
// `java.security.MessageDigest` does not resolve to the package.
import java.security.MessageDigest

plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.executable-jar")
    id("vitaminmcp.module-rules")
}

dependencies {
    implementation(project(":contract"))
    implementation(project(":bot-core"))

    // No protocol library, ever. This module chooses one at runtime and loads it in a class
    // loader of its own; putting one here would put it on the launcher's own class path, which
    // is the single thing the bundle exists to prevent.
}

/**
 * The backends carried inside this jar.
 *
 * Discovered rather than listed, so adding a protocol stays a directory and a coordinate. They
 * are embedded as **resources**, not merged as dependencies: two of them on one class path is
 * exactly the collision this design avoids, and shadow would happily produce it.
 */
val backends = rootProject.subprojects
    .filter { it.name.startsWith("backend-") }
    .sortedBy { it.name }

// Their tasks are read below, and a sibling project is not evaluated yet when this one is.
backends.forEach { evaluationDependsOn(it.path) }

require(backends.isNotEmpty()) {
    "No backend modules found. A runner with no backends cannot connect to anything."
}

val backendIndex = tasks.register("backendIndex") {
    description = "Lists the embedded backends for the launcher to choose from."

    val target = layout.buildDirectory.file("generated/backend-index/index.properties")
    val jars = backends.associate { backend ->
        backend.name to backend.tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }
    }

    outputs.file(target)
    jars.values.forEach { inputs.file(it) }

    doLast {
        val file = target.get().asFile
        file.parentFile.mkdirs()
        file.writeText(buildString {
            jars.forEach { (module, jar) ->
                val protocol = module.removePrefix("backend-")
                appendLine("protocol.$protocol=$module")

                // The content hash keys the extraction directory. Keying it on the project
                // version instead looked equivalent and was not: the version does not change
                // between two builds during development, so a rebuilt backend went on running
                // from the previously unpacked copy — a fix that was in the jar, was not in the
                // cache, and reported as though it had never been made.
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(jar.get().asFile.readBytes())
                appendLine("hash.$protocol=" + digest.take(8)
                    .joinToString("") { byte -> "%02x".format(byte) })
            }
        })
    }
}

tasks.processResources {
    into("backends") {
        from(backendIndex)
        backends.forEach { backend ->
            from(backend.tasks.named("shadowJar")) {
                // Renamed away from `.jar` deliberately. Shadow inspects every file it packages
                // and *unzips* anything that looks like an archive, so a backend added as
                // `backend-772.jar` arrives exploded into the bundle — its MCProtocolLib on the
                // launcher's own class path, which is precisely the collision this design
                // exists to prevent. The extension is what stops that, and it is not cosmetic.
                rename { "${backend.name}.backend" }
            }
        }
    }
}

tasks.shadowJar {
    archiveBaseName = "bot-runner"
    manifest {
        attributes("Main-Class" to "moe.vitamin.minecraft.mcp.bot.launcher.RunnerLauncher")
    }
}
