pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "VitaminMCP"

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

file("bot/backends").listFiles()
    ?.filter { it.isDirectory && it.name.startsWith("backend-") }
    ?.sortedBy { it.name }
    ?.forEach { directory ->
        include(directory.name)
        project(":${directory.name}").projectDir = directory
    }
