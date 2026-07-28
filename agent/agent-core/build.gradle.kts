plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.module-rules")
}

repositories {
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
        name = "spigot-snapshots"
    }
    // spigot-api pulls net.md-5:bungeecord-chat, which Spigot's own repo no longer serves for
    // 1.13. Paper's repo still mirrors it.
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    api(project(":contract"))

    // The supported floor is 1.13 (docs/design.md §5), and compiling against the floor is what
    // guarantees a single jar runs across the whole range. Paper's own API cannot be used for
    // this: repo.papermc.io no longer publishes anything below 1.16.5, and io.papermc.paper
    // only starts at 1.17. Spigot's API is the Bukkit API at 1.13.2, which is exactly what
    // design.md §5 asks for ("Bukkit API만으로 단일 jar가 전 지원 범위에 동작").
    //
    // Consequence: Paper-only methods are not on the compile classpath. Anything that needs
    // one (server TPS) reaches it reflectively and degrades cleanly when it is absent.
    compileOnly("org.spigotmc:spigot-api:1.13.2-R0.1-SNAPSHOT")

    // Bukkit has no "subscribe to every event" API, so the event classes are discovered by
    // scanning. Relocated into the plugin jar by vitaminmcp.shadow-conventions.
    implementation("io.github.classgraph:classgraph:4.8.186")

    // Attaching the log4j2 appender is the one place CLAUDE.md allows a direct log4j2
    // reference. Compiled against 2.8.1 — the oldest 2.x a supported server ships — so the
    // appender only binds API present in every version it will run on.
    compileOnly("org.apache.logging.log4j:log4j-core:2.8.1")

    // spigot-api's class files reference @Contract. Without the annotations on the compile
    // classpath, -Xlint:all reports "cannot find annotation method" for every Bukkit type
    // touched, which buries real warnings.
    compileOnly("org.jetbrains:annotations:24.1.0")

    testImplementation("org.spigotmc:spigot-api:1.13.2-R0.1-SNAPSHOT")
}
