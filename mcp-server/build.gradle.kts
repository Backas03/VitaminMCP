plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.executable-jar")
    id("vitaminmcp.module-rules")
}

dependencies {
    implementation(project(":testkit"))
    // Named explicitly rather than leaned on through testkit: this module uses BotPool and
    // BotSession directly, and depending on a type that reaches you transitively breaks the
    // moment the module in between reorganises its own dependencies.
    implementation(project(":bot-core"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}

// One jar, because an MCP client configuration is a single command line. A start script with a
// lib/ directory beside it works too, but it makes the thing a user has to write in their client
// config depend on where they unpacked it, and that is the step installs get wrong.
tasks.shadowJar {
    archiveBaseName = "mcp-server"
    manifest {
        attributes("Main-Class" to "moe.vitamin.minecraft.mcp.server.VitaminMcpServer")
    }
}

// Invariant 1: this module must never compile against agent-*. The agent is a jar injected
// into whichever server is under test, and the only thing joining the two is the contract —
// which is what allows a different agent build per Minecraft version without anything here
// changing. checkModuleDependencies enforces it.
