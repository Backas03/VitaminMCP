plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.module-rules")
}

dependencies {
    api(project(":contract"))
    implementation(project(":bot-core"))
    implementation(project(":bot-via"))
    implementation(project(":orchestrator"))
}

// Scenario runner, wait_for, assertions (Stage 3). No fixed sleeps, ever.
