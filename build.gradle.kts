evaluationDependsOnChildren()

/** Collects the jars an install is made of. */

val dist by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Gathers the agent plugin, the MCP server and the bot runner into build/dist."

    from(project(":agent-mcp").tasks.named("shadowJar"))
    from(project(":mcp-server").tasks.named("shadowJar"))
    from(project(":bot-runner").tasks.named("shadowJar"))

    into(layout.buildDirectory.dir("dist"))
}
