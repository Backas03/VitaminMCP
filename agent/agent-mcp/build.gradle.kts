import moe.vitamin.build.SupportedVersions

plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.server-jvm-target")
    id("vitaminmcp.module-rules")
    id("vitaminmcp.shadow-conventions")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    implementation(project(":agent-core"))

    // JSON only. The HTTP transport is the JDK's own com.sun.net.httpserver — pulling in
    // Javalin or Undertow would add a servlet stack, a second Netty and more to relocate, for
    // a handful of endpoints that answer one JSON-RPC method each (docs/design.md §7).
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    compileOnly(SupportedVersions.paperApiCoordinate)
    compileOnly("org.jetbrains:annotations:24.1.0")

    testImplementation(SupportedVersions.paperApiCoordinate)
}

// The shipped plugin jar. Named without the version so server operators can drop in a
// replacement without editing scripts.
tasks.shadowJar {
    archiveBaseName = "VitaminMCP"
    archiveVersion = ""
}

// plugin.yml is filled in from the build rather than maintained by hand: apiVersion in
// particular has to agree with the version the agent is compiled against, and a plugin.yml
// claiming a floor the bytecode cannot meet is exactly the mismatch SupportedVersions exists
// to prevent.
tasks.processResources {
    val properties = mapOf(
        "version" to version,
        "apiVersion" to SupportedVersions.pluginApiVersion,
    )
    inputs.properties(properties)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(properties)
    }
}
