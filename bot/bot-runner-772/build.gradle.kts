plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.executable-jar")
    id("vitaminmcp.module-rules")
}

dependencies {
    implementation(project(":contract"))
    implementation(project(":bot-core"))

    // The protocol this runner exists for. A version whose protocol differs gets a sibling
    // module with its own MCProtocolLib — they cannot share a classpath, since every build of
    // it occupies the same package names. That is the whole reason runners are processes.
    implementation("org.geysermc.mcprotocollib:protocol:1.21.7-1")
    implementation("net.kyori:adventure-text-serializer-plain:4.17.0")
}

tasks.shadowJar {
    archiveBaseName = "bot-runner-772"
    manifest {
        attributes("Main-Class" to "moe.vitamin.minecraft.mcp.bot.runner.RunnerMain")
    }
}

// Live tests connect to a real server, so they stay off unless asked for.
tasks.test {
    listOf("vitaminmcp.liveServer", "vitaminmcp.host", "vitaminmcp.port",
           "vitaminmcp.token", "vitaminmcp.mcpPort", "vitaminmcp.repeat",
           "vitaminmcp.debugHandshake").forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
    testLogging { showStandardStreams = true }
}
