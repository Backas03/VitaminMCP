plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.module-rules")
}

dependencies {
    api(project(":contract"))
}

// No protocol library here, deliberately. bot-core describes *what* a bot is asked to do and
// speaks to a runner process that does it; the protocol library lives in the runner. That is
// what lets one matrix run drive several protocol versions, and it means everything above this
// module is free of MCProtocolLib entirely.
