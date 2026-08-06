import moe.vitamin.build.SupportedVersions

plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.server-jvm-target")
    id("vitaminmcp.module-rules")
}

dependencies {
    api(project(":contract"))

    compileOnly(SupportedVersions.paperApiCoordinate)

    implementation("io.github.classgraph:classgraph:4.8.186")

    compileOnly("org.apache.logging.log4j:log4j-core:2.8.1")

    compileOnly("org.jetbrains:annotations:24.1.0")

    testImplementation(SupportedVersions.paperApiCoordinate)
}
