plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.module-rules")
    application
}

dependencies {
    implementation(project(":testkit"))
    // Named explicitly rather than leaned on through testkit: this module uses BotPool and
    // BotSession directly, and depending on a type that reaches you transitively breaks the
    // moment the module in between reorganises its own dependencies.
    implementation(project(":bot-core"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}

application {
    mainClass = "moe.vitamin.minecraft.mcp.server.VitaminMcpServer"
}

// Invariant 1: this module must never compile against agent-*. The agent is a jar injected
// into whichever server is under test, and the only thing joining the two is the contract —
// which is what allows a different agent build per Minecraft version without anything here
// changing. checkModuleDependencies enforces it.

// Writes the runtime classpath to a file so the server can be launched directly — which is how
// an MCP client starts it, and how it gets exercised without a Gradle wrapper in the way.
val writeClasspath by tasks.registering {
    val classpath = sourceSets.main.get().runtimeClasspath
    val target = layout.buildDirectory.file("tmp/classpath.txt")
    inputs.files(classpath)
    outputs.file(target)
    doLast {
        target.get().asFile.parentFile.mkdirs()
        target.get().asFile.writeText(classpath.joinToString(File.pathSeparator))
    }
}
