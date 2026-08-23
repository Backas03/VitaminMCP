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

// The dogfooding fixture is a deliberately broken Paper plugin, kept out of this repository
// because it is testing apparatus rather than anything a user installs. It is still a Gradle
// module wherever it exists, so it compiles against the same Paper floor as everything else and
// cannot rot silently — included only when the directory is there, so a clone without it builds.
if (file("dogfood/fixture-plugin").isDirectory) {
    include("dogfood")
}

project(":agent-core").projectDir = file("agent/agent-core")
project(":agent-mcp").projectDir = file("agent/agent-mcp")
project(":bot-core").projectDir = file("bot/bot-core")
if (findProject(":dogfood") != null) {
    project(":dogfood").projectDir = file("dogfood/fixture-plugin")
}
