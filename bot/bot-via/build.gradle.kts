plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.module-rules")
}

dependencies {
    implementation(project(":bot-core"))
}

// Embedded ViaProxy, so the bot only ever speaks one protocol version (Stage 5).
