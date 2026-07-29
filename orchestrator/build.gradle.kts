plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.module-rules")
}

dependencies {
    api(project(":contract"))
}

// Native server lifecycle, world resets, and the version matrix from versions.yaml.
// Docker was dropped: what is needed is "this version, with a clean world", which a jar in a
// fresh directory does in seconds (docs/design.md §15.1).

// Live tests download a Paper build and start it, so they stay off unless asked for:
//   ./gradlew :orchestrator:test -Dvitaminmcp.liveServer=true -Dvitaminmcp.agentJar=<path>
tasks.test {
    listOf("vitaminmcp.liveServer", "vitaminmcp.agentJar").forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
    testLogging { showStandardStreams = true }
}
