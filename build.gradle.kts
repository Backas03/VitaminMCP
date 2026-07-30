// Aggregator only — the root project holds no source.
//
// Everything shared lives in the `vitaminmcp.*` convention plugins under build-logic/:
//   vitaminmcp.java-conventions    Java 21 toolchain, compiler options, JUnit 5
//   vitaminmcp.module-rules        dependency-direction enforcement
//   vitaminmcp.shadow-conventions  shadow + relocation for jars that run inside a server JVM
//   vitaminmcp.executable-jar      shadow, no relocation, for jars we launch ourselves

// Needed because the task below names tasks in the subprojects, and the root script is
// configured before them.
evaluationDependsOnChildren()

/**
 * Collects the jars an install is made of.
 *
 * An install is three files that go to three different places, and which of them goes where is
 * the part that is easy to get wrong. Putting them in one directory means the instructions can
 * name a directory rather than three paths into `build/`, each a different shape.
 */
val dist by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Gathers the agent plugin, the MCP server and the bot runner into build/dist."

    // Named individually rather than swept up from every module: most modules are libraries
    // that are not part of an install, and a sweep would quietly start shipping one.
    from(project(":agent-mcp").tasks.named("shadowJar"))       // → server's plugins/
    from(project(":mcp-server").tasks.named("shadowJar"))      // → launched by the MCP client
    from(project(":bot-runner-772").tasks.named("shadowJar"))  // → launched by mcp-server

    into(layout.buildDirectory.dir("dist"))
}
