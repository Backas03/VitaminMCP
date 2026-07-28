plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.module-rules")
}

dependencies {
    api(project(":contract"))
}

// Docker server lifecycle, world resets, and the version matrix driven by versions.yaml
// (Stage 5, design.md §15).
