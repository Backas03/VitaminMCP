plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.executable-jar")
    id("vitaminmcp.module-rules")
}

dependencies {
    implementation(project(":testkit"))

    implementation(project(":bot-core"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}

tasks.shadowJar {
    archiveBaseName = "mcp-server"
    manifest {
        attributes("Main-Class" to "moe.vitamin.minecraft.mcp.server.VitaminMcpServer")
    }
}

tasks.test {
    listOf(
        "vitaminmcp.liveServer",
        "vitaminmcp.host",
        "vitaminmcp.port",
        "vitaminmcp.mcpPort",
        "vitaminmcp.token",
        "vitaminmcp.runnerJar",
    ).forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
    testLogging { showStandardStreams = true }
}
