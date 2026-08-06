import moe.vitamin.build.SupportedVersions

plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.server-jvm-target")
    id("vitaminmcp.module-rules")
    id("vitaminmcp.shadow-conventions")
}

dependencies {
    implementation(project(":agent-core"))

    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    compileOnly(SupportedVersions.paperApiCoordinate)
    compileOnly("org.jetbrains:annotations:24.1.0")

    testImplementation(SupportedVersions.paperApiCoordinate)
}

tasks.shadowJar {
    archiveBaseName = "VitaminMCP"
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
