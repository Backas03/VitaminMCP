plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.module-rules")
}

dependencies {
    api(project(":contract"))
    implementation(project(":bot-core"))
    implementation(project(":orchestrator"))

    // Scenarios are JSON, and so is the MCP conversation with the agent.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}

// Note what is absent, both enforced by vitaminmcp.module-rules:
//
//   agent-*        reached over MCP like any other client, which keeps the agent swappable per
//                  server version without this module knowing (CLAUDE.md invariant 1)
//   MCProtocolLib  bots run in a child process, so nothing here links a protocol library — and
//                  a matrix can therefore span versions whose protocols differ

// Live scenarios are skipped unless asked for, so `./gradlew build` needs no server:
//   ./gradlew :testkit:test -Dvitaminmcp.liveServer=true -Dvitaminmcp.token=...
tasks.test {
    listOf(
        "vitaminmcp.liveServer",
        "vitaminmcp.host",
        "vitaminmcp.port",
        "vitaminmcp.mcpPort",
        "vitaminmcp.token",
        "vitaminmcp.agentJar",
        "vitaminmcp.runnerJar",
    ).forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
    testLogging { showStandardStreams = true }
}
