plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.module-rules")
    id("vitaminmcp.shadow-conventions")
}

repositories {
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
        name = "spigot-snapshots"
    }
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

    compileOnly("org.spigotmc:spigot-api:1.13.2-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:24.1.0")

    testImplementation("org.spigotmc:spigot-api:1.13.2-R0.1-SNAPSHOT")
}

// The shipped plugin jar. Named without the version so server operators can drop in a
// replacement without editing scripts.
tasks.shadowJar {
    archiveBaseName = "VitaminMCP"
    archiveVersion = ""
}

// plugin.yml carries ${version} so the jar reports the build's version rather than a number
// that has to be kept in sync by hand.
tasks.processResources {
    val properties = mapOf("version" to version)
    inputs.properties(properties)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(properties)
    }
}
