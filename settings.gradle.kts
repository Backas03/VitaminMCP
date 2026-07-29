pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "VitaminMCP"

// Project paths are kept flat so that they match the module names used throughout
// CLAUDE.md and docs/design.md one-to-one (`:contract`, `:agent-core`, ...), while the
// `agent/` and `bot/` grouping is preserved on disk.
include(
    "contract",
    "agent-core",
    "agent-mcp",
    "bot-core",
    "bot-runner-772",
    "orchestrator",
    "testkit",
    "mcp-server",
)

project(":agent-core").projectDir = file("agent/agent-core")
project(":agent-mcp").projectDir = file("agent/agent-mcp")
project(":bot-core").projectDir = file("bot/bot-core")
project(":bot-runner-772").projectDir = file("bot/bot-runner-772")
