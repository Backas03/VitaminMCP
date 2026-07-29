import moe.vitamin.build.SupportedVersions

plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.server-jvm-target")
    id("vitaminmcp.module-rules")
}


dependencies {
    api(project(":contract"))

    // Compiled against the supported floor, which is what lets one jar run across the whole
    // range: API added later is simply not on the classpath, so it cannot be used by accident
    // and then fail at runtime on an older server.
    compileOnly(SupportedVersions.paperApiCoordinate)

    // Bukkit has no "subscribe to every event" API, so the event classes are discovered by
    // scanning. Relocated into the plugin jar by vitaminmcp.shadow-conventions.
    implementation("io.github.classgraph:classgraph:4.8.186")

    // Attaching the log4j2 appender is the one place CLAUDE.md allows a direct log4j2
    // reference. Compiled against 2.8.1 — older than anything a supported server ships — so
    // the appender only binds API present in every version it will run on.
    compileOnly("org.apache.logging.log4j:log4j-core:2.8.1")

    // paper-api's class files reference @Contract and @NotNull. Without the annotations on the
    // compile classpath, -Xlint:all reports "cannot find annotation method" for every Bukkit
    // type touched, which buries real warnings.
    compileOnly("org.jetbrains:annotations:24.1.0")

    testImplementation(SupportedVersions.paperApiCoordinate)
}
