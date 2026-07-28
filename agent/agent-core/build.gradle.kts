plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.module-rules")
}

dependencies {
    api(project(":contract"))
}

// Capture engine and state queries, on the Bukkit/Paper API only (invariant 3: no NMS).
//
// The Paper API coordinate is deliberately not pinned yet. The supported floor is 1.13,
// but `io.papermc.paper:paper-api` only starts at 1.17 — 1.13 needs the older
// `com.destroystokyo.paper:paper-api`. That call belongs to Stage 1b.
