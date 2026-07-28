plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.module-rules")
    id("vitaminmcp.shadow-conventions")
}

dependencies {
    implementation(project(":agent-core"))
}

// The MCP server that runs inside the Minecraft server, on the JDK's built-in
// com.sun.net.httpserver (design.md §7). This module produces the shipped plugin jar,
// which is why it carries the shadow + relocation conventions.
