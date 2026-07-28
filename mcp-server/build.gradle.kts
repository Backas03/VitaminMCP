plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.module-rules")
}

dependencies {
    implementation(project(":testkit"))
}

// Entrypoint — tool exposure and assembly only, no new logic (Stage 4).
//
// Invariant 1: this module must never compile against agent-*. The agent ships as a jar
// injected into a server at runtime, and the only thing joining the two is :contract.
// `checkModuleDependencies` enforces it.
