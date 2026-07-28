import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    // Applied so that `compileClasspath` and `check` exist no matter what order a module
    // lists its plugins in. Re-applying is a no-op.
    `java-library`
}

// Dependency direction, from CLAUDE.md ("의존은 한 방향으로만 흐른다") and docs/design.md §6:
//
//     mcp-server → testkit → {bot-core, bot-via, orchestrator, contract}
//     agent-mcp  → agent-core → contract
//
// The whitelist is exhaustive — a module with no entry here fails the build, so adding a
// module forces an explicit decision about what it may depend on. Invariant 1 (mcp-server
// must never compile against agent-*) falls out of this automatically: agent-core and
// agent-mcp appear in nobody's allowed set except each other's.
val allowedProjectDependencies: Map<String, Set<String>> = mapOf(
    ":contract" to emptySet(),
    ":agent-core" to setOf(":contract"),
    ":agent-mcp" to setOf(":agent-core", ":contract"),
    ":bot-core" to setOf(":contract"),
    ":bot-via" to setOf(":bot-core", ":contract"),
    ":orchestrator" to setOf(":contract"),
    ":testkit" to setOf(":bot-core", ":bot-via", ":orchestrator", ":contract"),
    ":mcp-server" to setOf(":testkit", ":bot-core", ":bot-via", ":orchestrator", ":contract"),
)

// The invariant is about what a module may *compile* against, so runtime-only and test
// configurations are deliberately out of scope.
val checkedConfigurations = listOf("api", "implementation", "compileOnly")

val modulePath = project.path

val checkModuleDependencies = tasks.register("checkModuleDependencies") {
    group = "verification"
    description = "Fails if this module declares a dependency the architecture does not allow."
}

// Dependencies are declared *after* this plugin is applied, so the whitelist can only be
// compared once the module's build script has finished evaluating. Both sides are captured
// here as plain strings, which keeps the task usable with the configuration cache.
afterEvaluate {
    val allowed = allowedProjectDependencies[modulePath]
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
                        append("See the dependency direction rules in CLAUDE.md.")
                    }
                )
            }
        }
    }
}

tasks.named("check") {
    dependsOn(checkModuleDependencies)
}

// CLAUDE.md invariant 2: contract holds pure Java types only. Checking the resolved
// classpath rather than the declared dependencies also catches anything that arrives
// transitively. Test configurations are excluded so contract can still use JUnit.
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
                        append("See invariant 2 in CLAUDE.md.")
                    }
                )
            }
        }
    }

    tasks.named("check") {
        dependsOn(checkContractIsDependencyFree)
    }
}
