import java.security.MessageDigest

plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.executable-jar")
    id("vitaminmcp.module-rules")
}

dependencies {
    implementation(project(":contract"))
    implementation(project(":bot-core"))

}

/** The backends carried inside this jar. */
val backends = rootProject.subprojects
    .filter { it.name.startsWith("backend-") }
    .sortedBy { it.name }

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
