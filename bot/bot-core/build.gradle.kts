plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.module-rules")
}

repositories {
    // MCProtocolLib and its cloudburstmc/nukkitx transitives.
    maven("https://repo.opencollab.dev/maven-releases/") {
        name = "opencollab-releases"
    }
    maven("https://repo.opencollab.dev/maven-snapshots/") {
        name = "opencollab-snapshots"
    }
}

dependencies {
    api(project(":contract"))

    // One protocol version only; older servers are reached through ViaProxy in bot-via
    // (docs/design.md §4). There is no 1.21.8 release because 1.21.7 and 1.21.8 share
    // protocol 772 — 1.21.8 was a bugfix with no wire change — so this is the build that
    // speaks to the supported floor.
    implementation("org.geysermc.mcprotocollib:protocol:1.21.7-1")
}

// Deliberately not applying vitaminmcp.server-jvm-target: this module runs in our own JVM,
// never inside a Minecraft server, so the floor's bytecode constraint does not apply to it.
