pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "VitaminMCP"

include(
    "contract",
    "agent-core",
    "agent-mcp",
    "bot-core",
    "orchestrator",
    "testkit",
    "mcp-server",
)

project(":agent-core").projectDir = file("agent/agent-core")
project(":agent-mcp").projectDir = file("agent/agent-mcp")
project(":bot-core").projectDir = file("bot/bot-core")
