import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {

    `java-library`
}

val allowedProjectDependencies: Map<String, Set<String>> = mapOf(
    ":contract" to emptySet(),
    ":agent-core" to setOf(":contract"),
    ":agent-mcp" to setOf(":agent-core", ":contract"),
    ":bot-core" to setOf(":contract"),
    ":bot-runner" to setOf(":bot-core", ":contract"),
    ":orchestrator" to setOf(":contract"),
    ":testkit" to setOf(":bot-core", ":orchestrator", ":contract"),
    ":mcp-server" to setOf(":testkit", ":bot-core", ":orchestrator", ":contract"),
)

fun allowedFor(path: String): Set<String>? = when {
    allowedProjectDependencies.containsKey(path) -> allowedProjectDependencies[path]
    path.startsWith(":backend-") -> setOf(":bot-core", ":contract")
    else -> null
}

val checkedConfigurations = listOf("api", "implementation", "compileOnly")

val modulePath = project.path

val checkModuleDependencies = tasks.register("checkModuleDependencies") {
    group = "verification"
    description = "Fails if this module declares a dependency the architecture does not allow."
}

afterEvaluate {
    val allowed = allowedFor(modulePath)
    val declared = checkedConfigurations
        .mapNotNull { configurations.findByName(it) }
        .flatMap { it.dependencies.withType(ProjectDependency::class.java) }
        .map { it.path }
        .distinct()
        .sorted()

    checkModuleDependencies.configure {
        inputs.property("allowed", allowed?.sorted() ?: listOf("<undeclared>"))
        inputs.property("declared", declared)

        doLast {
            if (allowed == null) {
                throw GradleException(
                    "$modulePath has no entry in allowedProjectDependencies. Add one to " +
                        "build-logic/src/main/kotlin/vitaminmcp.module-rules.gradle.kts."
                )
            }

            val violations = declared - allowed
            if (violations.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("Dependency direction violation in $modulePath:")
                        violations.forEach { appendLine("    $modulePath  ->  $it") }
                        appendLine(
                            "  allowed: " +
                                if (allowed.isEmpty()) "(nothing)" else allowed.sorted().joinToString(", ")
                        )
                        append("See the dependency direction rules in CONTRIBUTING.md.")
                    }
                )
            }
        }
    }
}

tasks.named("check") {
    dependsOn(checkModuleDependencies)
}

if (modulePath == ":contract") {
    fun externalArtifactsOf(configurationName: String) =
        configurations.named(configurationName).flatMap { configuration ->
            configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
                artifacts
                    .map { it.id.componentIdentifier }
                    .filterIsInstance<ModuleComponentIdentifier>()
                    .map { "${it.group}:${it.module}:${it.version}" }
                    .distinct()
                    .sorted()
            }
        }

    val onCompileClasspath = externalArtifactsOf("compileClasspath")
    val onRuntimeClasspath = externalArtifactsOf("runtimeClasspath")

    val checkContractIsDependencyFree = tasks.register("checkContractIsDependencyFree") {
        group = "verification"
        description = "Fails if :contract gains an external dependency."

        inputs.property("compileClasspath", onCompileClasspath)
        inputs.property("runtimeClasspath", onRuntimeClasspath)

        doLast {
            val offenders = (onCompileClasspath.get() + onRuntimeClasspath.get()).distinct().sorted()
            if (offenders.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("$modulePath must have no external dependencies, but found:")
                        offenders.forEach { appendLine("    $it") }
                        append("See invariant 2 in CONTRIBUTING.md.")
                    }
                )
            }
        }
    }

    tasks.named("check") {
        dependsOn(checkContractIsDependencyFree)
    }
}
