pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "VitaminMCP"

// Project paths are kept flat so that they match the module names used throughout
// CONTRIBUTING.md and docs/design.md one-to-one (`:contract`, `:agent-core`, ...), while the
// `agent/` and `bot/` grouping is preserved on disk.
include(
    "contract",
    "agent-core",
    "agent-mcp",
    "bot-core",
    "bot-runner",
    "orchestrator",
    "testkit",
    "mcp-server",
)

project(":agent-core").projectDir = file("agent/agent-core")
project(":agent-mcp").projectDir = file("agent/agent-mcp")
project(":bot-core").projectDir = file("bot/bot-core")
project(":bot-runner").projectDir = file("bot/bot-runner")

// Backends are found rather than listed, which is what makes adding a protocol a directory and
// a coordinate. The directory name carries the protocol number and the convention plugin derives
// everything else from it (docs/multi-version.md §2.2).
file("bot/backends").listFiles()
    ?.filter { it.isDirectory && it.name.startsWith("backend-") }
    ?.sortedBy { it.name }
    ?.forEach { directory ->
        include(directory.name)
        project(":${directory.name}").projectDir = directory
    }
