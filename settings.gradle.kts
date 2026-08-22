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

    // Not shipped. A deliberately broken Paper plugin that the dogfooding harness debugs — see
    // dogfood/README.md. It is a Gradle module so it compiles against the same Paper floor as
    // everything else and cannot rot silently.
    "dogfood",
)

project(":agent-core").projectDir = file("agent/agent-core")
project(":agent-mcp").projectDir = file("agent/agent-mcp")
project(":bot-core").projectDir = file("bot/bot-core")
project(":dogfood").projectDir = file("dogfood/fixture-plugin")
