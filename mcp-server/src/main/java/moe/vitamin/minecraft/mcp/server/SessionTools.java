package moe.vitamin.minecraft.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import moe.vitamin.minecraft.mcp.bot.core.BotRunner;
import moe.vitamin.minecraft.mcp.testkit.AgentClient;
import moe.vitamin.minecraft.mcp.testkit.ScenarioResult;

/**
 * The tools this server exposes, and nothing else.
 *
 * <p>Deliberately thin (docs/roadmap.md Stage 4): everything here forwards to testkit, bot-core
 * or the agent. A behaviour implemented at this layer would be one that scenarios written
 * against testkit directly could not use, and one with nowhere to be tested without starting an
 * MCP server.
 *
 * <p>The agent's own tools are proxied rather than reimplemented. That keeps one definition of
 * what {@code events_query} means, and it is what lets a matrix of servers be exposed later by
 * prefixing names rather than by writing a second set of tools.
 */
final class SessionTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Agent tools passed straight through, in the order a caller usually needs them. */
    private static final List<String> PROXIED = List.of(
            "server_info", "events_summary", "events_query", "logs_query",
            "exceptions_recent", "state_query", "wait_for", "command_exec");

    private Session session;

    ArrayNode listTools() {
        ArrayNode tools = MAPPER.createArrayNode();

        tools.add(tool("session_start",
                "Connect to a Minecraft server and its VitaminMCP agent. Call this first — "
                        + "every other tool needs it.",
                properties -> {
                    string(properties, "host", "Server host. Defaults to 127.0.0.1.");
                    number(properties, "port", "Minecraft port. Defaults to 25565.");
                    number(properties, "mcpPort", "Agent's MCP port. Defaults to 25585.");
                    string(properties, "token", "The agent's auth-token from its config.yml.");
                    string(properties, "runnerJar",
                            "Path to the bot runner jar. Optional: defaults to the "
                                    + "bot-runner-*.jar sitting beside this server's own jar, "
                                    + "which is where 'gradlew dist' puts it.");
                    string(properties, "tls",
                            "'true' if the agent serves HTTPS. Required for any server that is "
                                    + "not on this machine — a remotely reachable agent refuses "
                                    + "to start without transport security.");
                    string(properties, "tlsFingerprint",
                            "SHA-256 of the agent's certificate, printed in its startup log. "
                                    + "Needed when the agent uses a self-signed certificate; "
                                    + "pins that exact certificate so nothing has to be "
                                    + "installed on this machine. Omit for a certificate signed "
                                    + "by a public authority.");
                }));

        tools.add(tool("session_reset",
                "Disconnect every bot, keeping the connection. Use between independent tests so "
                        + "one does not inherit the other's players.",
                properties -> {}));

        tools.add(tool("bot_spawn",
                "Connect a bot and wait until it is standing in the world. Its UUID is derived "
                        + "from its name, so the same name is the same player every run and "
                        + "permission-dependent behaviour is reproducible.",
                properties -> {
                    string(properties, "name", "Bot name, at most 16 characters.");
                    string(properties, "clientIp",
                            "Address the server should attribute the connection to. Omit unless "
                                    + "you are testing something keyed on the address — an IP "
                                    + "ban, a per-IP limit, geo logic. Omitted, the bot reports "
                                    + "the address it really connects from.");
                }));

        tools.add(tool("bot_inspect",
                "What the bot's client was told, which the server cannot always be asked. Use "
                        + "when state_query reports an empty menu but a player would see a full "
                        + "one — a plugin drawing its GUI with packets leaves the server-side "
                        + "inventory empty. Also returns the messages the server sent this bot, "
                        + "which is where a refusal like 'you lack permission' appears; those "
                        + "never reach the console, so a declined command and one that did "
                        + "nothing look identical from the agent's side. Item ids are numeric "
                        + "here — the protocol carries no names. 'messages' also covers action "
                        + "bar, title and subtitle text, each prefixed with where it appeared, "
                        + "since a plugin is as likely to refuse above the hotbar as in chat. "
                        + "'bossBars' and 'scoreboard' are on-screen state rather than messages: "
                        + "they persist, and a server's live view of a player — timers, money, "
                        + "region, quest progress — is usually drawn there and nowhere the agent "
                        + "can see.",
                properties -> string(properties, "name", "Bot name.")));

        tools.add(tool("bot_run_scenario",
                "Run a declarative scenario. Steps: spawn, despawn, move_to, break_block, "
                        + "use_block, use_entity, command, chat, console, click_slot, "
                        + "close_menu, wait_for, assert_block, assert_player, assert_event, "
                        + "assert_inventory, assert_message. There is no sleep step — use "
                        + "wait_for and name what you are waiting for. To test a menu GUI: "
                        + "command, then wait_for inventory_open, then assert_inventory with the "
                        + "slots you expect. use_entity right-clicks an NPC or villager, named by "
                        + "the coordinates it stands at rather than by an entity id, since the id "
                        + "is the server's own and never visible here. On failure the response "
                        + "says which step failed, why, and what the server was doing at that "
                        + "moment.",
                properties -> string(properties, "scenario",
                        "JSON array of steps, e.g. "
                                + "[{\"action\":\"spawn\",\"bot\":\"Tester1\"}]")));

        // Proxied verbatim: one definition of each tool, living where it is implemented.
        //
        // The parameters are not restated here, and the schema says so by accepting any
        // property rather than declaring one. An earlier version declared a single string
        // called "arguments", which was worse than saying nothing: a caller that believed it
        // sent {"arguments": "{...}"}, the agent saw no parameters at all, and the failure came
        // back as "state_query needs 'kind'" — blaming the caller for following the schema.
        //
        // What the parameters actually are comes from the agent itself, in session_start's
        // response. That keeps one definition of each tool, in the module that implements it.
        for (String name : PROXIED) {
            tools.add(passthroughTool(name,
                    "Forwarded to the agent on the connected server. Call session_start first — "
                            + "its response lists this tool's parameters, as the agent defines "
                            + "them. Pass them as top-level properties, not wrapped."));
        }
        return tools;
    }

    JsonNode call(String name, JsonNode arguments) {
        JsonNode args = arguments == null || arguments.isNull() ? MAPPER.createObjectNode() : arguments;

        return switch (name) {
            case "session_start" -> sessionStart(args);
            case "session_reset" -> sessionReset();
            case "bot_spawn" -> botSpawn(args);
            case "bot_inspect" -> botInspect(args);
            case "bot_run_scenario" -> runScenario(args);
            default -> {
                if (PROXIED.contains(name)) {
                    yield require().agent().call(name, (ObjectNode) args);
                }
                throw new IllegalArgumentException("Unknown tool: " + name);
            }
        };
    }

    private JsonNode sessionStart(JsonNode args) {
        if (session != null) {
            session.close();
        }
        String token = args.path("token").asText("");
        if (token.isBlank()) {
            throw new IllegalArgumentException(
                    "session_start needs 'token' — the agent refuses unauthenticated requests. "
                            + "It is the auth-token in the agent's config.yml.");
        }

        String runnerJar = args.path("runnerJar").asText("");
        java.nio.file.Path runner = runnerJar.isBlank()
                ? runnerBesideThisJar()
                : java.nio.file.Path.of(runnerJar);

        try {
            session = new Session(
                    args.path("host").asText("127.0.0.1"),
                    args.path("port").asInt(25565),
                    args.path("mcpPort").asInt(25585),
                    token,
                    args.path("tls").asBoolean(false),
                    args.path("tlsFingerprint").asText(null),
                    runner);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not start the bot runner: " + e.getMessage(), e);
        }

        // Called immediately so a bad host, port or token fails here with a clear message
        // rather than later inside an unrelated tool.
        JsonNode info = session.agent().call("server_info", AgentClient.arguments());

        ObjectNode result = MAPPER.createObjectNode();
        result.put("connected", session.describe());
        result.set("server", info);

        // The proxied tools' real parameters, straight from the agent that implements them.
        // They cannot be in this server's own tool list: that list is published at startup,
        // before any agent is connected, and which tools exist depends on the agent — a
        // read-only one does not expose command_exec at all.
        result.set("agentTools", session.agent().listTools());
        return result;
    }

    private JsonNode sessionReset() {
        try {
            require().reset();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "Bots were disconnected, but the replacement runner did not start: "
                            + e.getMessage() + ". Call session_start again.", e);
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.put("reset", true);
        result.put("session", session.describe());
        return result;
    }

    private JsonNode botSpawn(JsonNode args) {
        String name = args.path("name").asText("");
        if (name.isBlank()) {
            throw new IllegalArgumentException("bot_spawn needs 'name'.");
        }
        try {
            BotRunner.BotHandle bot = require().bots().spawn(
                    name, args.hasNonNull("clientIp") ? args.get("clientIp").asText() : null);

            ObjectNode result = MAPPER.createObjectNode();
            result.put("name", name);
            result.put("uuid",
                    moe.vitamin.minecraft.mcp.bot.core.BotIdentity.offlineUuid(name).toString());
            result.put("x", bot.blockX());
            result.put("y", bot.blockY());
            result.put("z", bot.blockZ());
            return result;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not spawn " + name + ": " + e.getMessage(), e);
        }
    }

    private JsonNode botInspect(JsonNode args) {
        String name = args.path("name").asText("");
        if (name.isBlank()) {
            throw new IllegalArgumentException("bot_inspect needs 'name'.");
        }
        try {
            BotRunner.ClientView view = new BotRunner.BotHandle(
                    require().bots(), name, 0, 0, 0).inspect();

            ObjectNode result = MAPPER.createObjectNode();
            if (view.menu() == null) {
                result.putNull("menu");
            } else {
                ObjectNode menu = result.putObject("menu");
                menu.put("containerId", view.menu().containerId());
                menu.put("title", view.menu().title());
            }

            ArrayNode items = result.putArray("items");
            for (BotRunner.MenuItem item : view.items()) {
                ObjectNode entry = items.addObject();
                entry.put("slot", item.slot());
                entry.put("itemId", item.itemId());
                entry.put("amount", item.amount());
                entry.put("name", item.name());
                entry.put("customModelData", item.customModelData());
                entry.put("lore", item.lore());
            }

            ArrayNode messages = result.putArray("messages");
            view.messages().forEach(messages::add);

            ArrayNode bossBars = result.putArray("bossBars");
            for (BotRunner.BossBar bar : view.bossBars()) {
                ObjectNode entry = bossBars.addObject();
                entry.put("title", bar.title());
                entry.put("progress", bar.progress());
                entry.put("color", bar.color());
            }

            if (view.scoreboard() == null) {
                result.putNull("scoreboard");
            } else {
                ObjectNode scoreboard = result.putObject("scoreboard");
                scoreboard.put("title", view.scoreboard().title());
                ArrayNode lines = scoreboard.putArray("lines");
                view.scoreboard().lines().forEach(lines::add);
            }
            return result;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not inspect " + name + ": " + e.getMessage(), e);
        }
    }

    private JsonNode runScenario(JsonNode args) {
        String scenario = args.path("scenario").isTextual()
                ? args.path("scenario").asText()
                : args.path("scenario").toString();
        if (scenario.isBlank() || "null".equals(scenario)) {
            throw new IllegalArgumentException("bot_run_scenario needs 'scenario'.");
        }

        ScenarioResult result = require().runner().run(scenario);

        ObjectNode response = MAPPER.createObjectNode();
        response.put("passed", result.passed());
        response.put("summary", result.describe());

        ArrayNode steps = response.putArray("steps");
        for (ScenarioResult.StepResult step : result.steps()) {
            ObjectNode entry = steps.addObject();
            entry.put("step", step.index());
            entry.put("action", step.action());
            entry.put("passed", step.passed());
            entry.put("detail", step.detail());
            if (!step.evidence().isEmpty()) {
                // Attached here rather than left for a follow-up call: the DoD is that a
                // failure arrives with what is needed to understand it, and a second round trip
                // reaches a server whose state has already moved on.
                entry.put("evidence", step.evidence());
            }
        }
        return response;
    }

    /**
     * Finds the bot runner next to this server's own jar.
     *
     * <p>{@code gradlew dist} puts the three jars in one directory, so the caller knowing where
     * this one is means they already know where the runner is. Asking them to type an absolute
     * path to a file we can see from here was friction for nothing.
     *
     * <p>Refuses to guess when there is more than one. Runners are named for the protocol they
     * speak, and picking the wrong one produces "Outdated client!" from the server — a failure
     * far enough from the cause to be worth avoiding.
     */
    private static java.nio.file.Path runnerBesideThisJar() {
        java.nio.file.Path here;
        try {
            here = java.nio.file.Path.of(SessionTools.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getParent();
        } catch (RuntimeException | java.net.URISyntaxException e) {
            throw new IllegalArgumentException(
                    "session_start needs 'runnerJar': this server could not work out where its "
                            + "own jar is, so it cannot find the runner beside it.");
        }

        List<java.nio.file.Path> found = new java.util.ArrayList<>();
        try (var entries = java.nio.file.Files.list(here)) {
            entries.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith("bot-runner-") && name.endsWith(".jar");
            }).forEach(found::add);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(
                    "session_start needs 'runnerJar': could not look in " + here + " (" + e + ")");
        }

        if (found.isEmpty()) {
            throw new IllegalArgumentException(
                    "session_start needs 'runnerJar' — the bot runner built for this server's "
                            + "protocol version. One JVM cannot speak two Minecraft protocols, so "
                            + "bots run in a child process. No bot-runner-*.jar was found in "
                            + here + ", so pass its path.");
        }
        if (found.size() > 1) {
            throw new IllegalArgumentException(
                    "session_start needs 'runnerJar': " + here + " holds more than one runner "
                            + found.stream().map(p -> p.getFileName().toString()).toList()
                            + ". Name the one that speaks this server's protocol.");
        }
        return found.get(0);
    }

    private Session require() {
        if (session == null) {
            throw new IllegalStateException("No session. Call session_start first.");
        }
        return session;
    }

    void close() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    // ---------------------------------------------------------------- schema

    private static ObjectNode tool(
            String name, String description, java.util.function.Consumer<ObjectNode> properties) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        properties.accept(schema.putObject("properties"));
        schema.set("required", MAPPER.createArrayNode());
        return tool;
    }

    /**
     * A tool whose arguments belong to something else.
     *
     * <p>Declares an object with no named properties and {@code additionalProperties} allowed,
     * which is the accurate description of a passthrough: whatever arrives is forwarded as-is.
     */
    private static ObjectNode passthroughTool(String name, String description) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);

        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        schema.putObject("properties");
        schema.put("additionalProperties", true);
        return tool;
    }

    private static void string(ObjectNode properties, String name, String description) {
        ObjectNode property = properties.putObject(name);
        property.put("type", "string");
        property.put("description", description);
    }

    private static void number(ObjectNode properties, String name, String description) {
        ObjectNode property = properties.putObject(name);
        property.put("type", "integer");
        property.put("description", description);
    }
}
