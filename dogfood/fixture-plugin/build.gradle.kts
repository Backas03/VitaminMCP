import moe.vitamin.build.SupportedVersions

plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.server-jvm-target")
    id("vitaminmcp.module-rules")
}

// No shadow conventions and no dependencies to relocate: this plugin is meant to look like an
// ordinary one on a user's server, and anything bundled here would be something the harness has
// to reason about that a real plugin would not.
dependencies {
    compileOnly(SupportedVersions.paperApiCoordinate)
}

tasks.jar {
    archiveBaseName = "DogfoodFixture"
    archiveVersion = ""
}

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
