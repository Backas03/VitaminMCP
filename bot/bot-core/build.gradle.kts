plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.module-rules")
}

dependencies {
    api(project(":contract"))
}

// MCProtocolLib wrapper and the forwarding-handshake injection (Stage 2a).
