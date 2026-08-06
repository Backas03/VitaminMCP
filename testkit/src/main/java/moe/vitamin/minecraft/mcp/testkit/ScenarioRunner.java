package moe.vitamin.minecraft.mcp.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import moe.vitamin.minecraft.mcp.bot.core.BotRunner;

/** Runs a declarative scenario against a server. */
public final class ScenarioRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Long enough for a real condition, short enough that a wedged one fails the run. */
    private static final Duration DEFAULT_WAIT = Duration.ofSeconds(15);

    private final BotRunner bots;
    private final AgentClient agent;

    public ScenarioRunner(BotRunner bots, AgentClient agent) {
        this.bots = bots;
        this.agent = agent;
    }

    public ScenarioResult run(String scenarioJson) {
        JsonNode steps;
        try {
            steps = MAPPER.readTree(scenarioJson);
        } catch (IOException e) {
            return new ScenarioResult(false, List.of(ScenarioResult.StepResult.failed(
                    0, "parse", "the scenario is not valid JSON: " + e.getMessage(), "")));
        }
        if (!steps.isArray()) {
            return new ScenarioResult(false, List.of(ScenarioResult.StepResult.failed(
                    0, "parse", "a scenario is an array of steps", "")));
        }

        long scenarioStart = currentEventSequence();

        List<ScenarioResult.StepResult> results = new ArrayList<>();
        int index = 0;

        for (JsonNode step : steps) {
            index++;
            String action = step.path("action").asText("");
            if (action.isEmpty()) {
                results.add(ScenarioResult.StepResult.failed(
                        index, "?", "step has no 'action'", step.toString()));
                return new ScenarioResult(false, results);
            }

            try {
                results.add(execute(index, action, step, scenarioStart));
            } catch (RuntimeException e) {

                results.add(ScenarioResult.StepResult.failed(
                        index, action, String.valueOf(e.getMessage()), snapshot()));
                return new ScenarioResult(false, results);
            }

            if (!results.get(results.size() - 1).passed()) {
                return new ScenarioResult(false, results);
            }
        }
        return new ScenarioResult(true, results);
    }

    private ScenarioResult.StepResult execute(
            int index, String action, JsonNode step, long scenarioStart) {
        return switch (action) {
            case "spawn" -> {
                String name = required(step, "bot");
                try {

                    BotRunner.BotHandle bot = bots.spawn(
                            name, step.hasNonNull("clientIp") ? step.get("clientIp").asText() : null);
                    yield ScenarioResult.StepResult.ok(index, action,
                            name + " joined at " + bot.x() + ", " + bot.y() + ", " + bot.z());
                } catch (java.io.IOException e) {

                    throw new IllegalStateException(
                            "could not spawn " + name + ": " + e.getMessage(), e);
                }
            }

            case "despawn" -> {
                try {
                    bots.despawn(required(step, "bot"));
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(String.valueOf(e.getMessage()), e);
                }
                yield ScenarioResult.StepResult.ok(index, action, "disconnected");
            }

            case "move_to" -> {
                act(step, bot -> bot.moveTo(step.path("x").asDouble(), step.path("y").asDouble(), step.path("z").asDouble()));
                yield ScenarioResult.StepResult.ok(index, action, "sent");
            }

            case "break_block" -> {
                act(step, bot -> bot.breakBlock(step.path("x").asInt(), step.path("y").asInt(), step.path("z").asInt()));
                yield ScenarioResult.StepResult.ok(index, action, "sent");
            }

            case "command" -> {
                act(step, bot -> bot.command(required(step, "command")));
                yield ScenarioResult.StepResult.ok(index, action, "sent");
            }

            case "chat" -> {
                act(step, bot -> bot.chat(required(step, "message")));
                yield ScenarioResult.StepResult.ok(index, action, "sent");
            }

            case "console" -> {
                ObjectNode arguments = AgentClient.arguments();
                arguments.put("command", required(step, "command"));
                JsonNode result = agent.call("command_exec", arguments);
                yield ScenarioResult.StepResult.ok(index, action,
                        "dispatched=" + result.path("dispatched").asBoolean()
                                + " output=" + result.path("output"));
            }

            case "wait_for" -> {
                JsonNode result = agent.call("wait_for", waitArguments(step));
                yield result.path("matched").asBoolean()
                        ? ScenarioResult.StepResult.ok(index, action,
                                "matched after " + result.path("ticksObserved").asInt() + " ticks")
                        : ScenarioResult.StepResult.failed(index, action,
                                "timed out waiting for " + result.path("condition").asText(),
                                "events=" + result.path("recentEvents")
                                        + " logs=" + result.path("recentLogs"));
            }

            case "assert_block" -> {
                ObjectNode arguments = AgentClient.arguments();
                arguments.put("kind", "block");
                arguments.put("world", step.path("world").asText("world"));
                arguments.put("x", step.path("x").asInt());
                arguments.put("y", step.path("y").asInt());
                arguments.put("z", step.path("z").asInt());
                String actual = agent.call("state_query", arguments).path("block").asText();
                String expected = required(step, "material").toUpperCase(java.util.Locale.ROOT);
                yield actual.equals(expected)
                        ? ScenarioResult.StepResult.ok(index, action, expected)
                        : ScenarioResult.StepResult.failed(index, action,
                                "expected " + expected + " but found " + actual, snapshot());
            }

            case "assert_player" -> {

                ObjectNode arguments = waitArguments(step);
                arguments.put("condition", "player_state");
                arguments.put("name", required(step, "bot"));

                JsonNode result = agent.call("wait_for", arguments);
                if (result.path("matched").asBoolean()) {
                    yield ScenarioResult.StepResult.ok(index, action, "as expected");
                }

                ObjectNode query = AgentClient.arguments();
                query.put("kind", "player");
                query.put("target", required(step, "bot"));
                JsonNode actual = agent.call("state_query", query);
                yield ScenarioResult.StepResult.failed(index, action,
                        "player state never matched", actual.toString());
            }

            case "use_block" -> {
                act(step, bot -> bot.useBlock(
                        step.path("x").asInt(), step.path("y").asInt(), step.path("z").asInt(),
                        step.path("face").asText(null)));
                yield ScenarioResult.StepResult.ok(index, action, "sent");
            }

            case "use_entity" -> {

                String[] hit = new String[1];
                act(step, bot -> hit[0] = bot.useEntity(
                        step.path("x").asDouble(), step.path("y").asDouble(),
                        step.path("z").asDouble(),
                        step.path("radius").asDouble(2.0),
                        step.path("entityType").asText(null)));
                yield ScenarioResult.StepResult.ok(index, action,
                        hit[0] == null || hit[0].isBlank()
                                ? "sent"
                                : "sent to entity " + hit[0]);
            }

            case "click_slot" -> {
                act(step, bot -> bot.clickSlot(
                        step.path("slot").asInt(), step.path("click").asText("left")));
                yield ScenarioResult.StepResult.ok(index, action, "sent");
            }

            case "close_menu" -> {
                act(step, BotRunner.BotHandle::closeMenu);
                yield ScenarioResult.StepResult.ok(index, action, "sent");
            }

            case "assert_inventory" -> {
                ObjectNode query = AgentClient.arguments();
                query.put("kind", "inventory");
                query.put("target", required(step, "bot"));
                query.put("which", step.path("which").asText("menu"));
                JsonNode actual = agent.call("state_query", query);

                yield checkInventory(index, action, step, actual);
            }

            case "assert_message" -> {

                String bot = required(step, "bot");
                String wanted = required(step, "contains");
                try {
                    List<String> received =
                            new BotRunner.BotHandle(bots, bot, 0, 0, 0).inspect().messages();
                    yield received.stream().anyMatch(line -> line.contains(wanted))
                            ? ScenarioResult.StepResult.ok(index, action, "said to " + bot)
                            : ScenarioResult.StepResult.failed(index, action,
                                    "nothing said to " + bot + " contained '" + wanted + "'",
                                    String.join(" | ", received));
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(String.valueOf(e.getMessage()), e);
                }
            }

            case "assert_event" -> {
                ObjectNode arguments = waitArguments(step);
                arguments.put("condition", "event");
                if (!arguments.has("sinceSequence")) {
                    arguments.put("sinceSequence", scenarioStart);
                }
                JsonNode result = agent.call("wait_for", arguments);
                yield result.path("matched").asBoolean()
                        ? ScenarioResult.StepResult.ok(index, action, "seen")
                        : ScenarioResult.StepResult.failed(index, action,
                                "no " + step.path("eventType").asText() + " was recorded",
                                "events=" + result.path("recentEvents"));
            }

            case "sleep" -> throw new IllegalArgumentException(
                    "there is no sleep step. Use wait_for and name what you are waiting for — a "
                            + "fixed wait is right on the machine that wrote it and wrong "
                            + "everywhere else (docs/roadmap.md Stage 3).");

            default -> throw new IllegalArgumentException("unknown action '" + action + "'");
        };
    }

    /** Checks a menu against what the step said should be in it. */
    private ScenarioResult.StepResult checkInventory(
            int index, String action, JsonNode step, JsonNode snapshot) {

        String view = snapshot.path("view").asText();
        if (step.hasNonNull("title") || "menu".equals(step.path("which").asText("menu"))) {

            if (!moe.vitamin.minecraft.mcp.contract.InventorySnapshot.isMenu(view)) {
                return ScenarioResult.StepResult.failed(index, action,
                        "no menu is open for " + step.path("bot").asText()
                                + " — the view is " + view + ". If a command should have opened "
                                + "one, wait_for inventory_open first.",
                        snapshot.toString());
            }
        }

        String wantedTitle = step.path("title").asText(null);
        if (wantedTitle != null && !plain(snapshot.path("title").asText("")).contains(wantedTitle)) {
            return ScenarioResult.StepResult.failed(index, action,
                    "expected the title to contain '" + wantedTitle + "' but it was '"
                            + snapshot.path("title").asText("") + "'",
                    snapshot.toString());
        }

        if (step.hasNonNull("size") && snapshot.path("size").asInt() != step.path("size").asInt()) {
            return ScenarioResult.StepResult.failed(index, action,
                    "expected " + step.path("size").asInt() + " slots but the menu has "
                            + snapshot.path("size").asInt(),
                    snapshot.toString());
        }

        int checked = 0;
        for (JsonNode expected : step.path("slots")) {
            int slot = expected.path("slot").asInt();
            JsonNode actual = slotIn(snapshot, slot);
            checked++;

            if (expected.path("empty").asBoolean(false)) {
                if (actual != null) {
                    return ScenarioResult.StepResult.failed(index, action,
                            "expected slot " + slot + " to be empty but it held "
                                    + actual.path("material").asText(),
                            snapshot.toString());
                }
                continue;
            }

            if (actual == null) {
                return ScenarioResult.StepResult.failed(index, action,
                        "slot " + slot + " is empty, expected "
                                + expected.path("material").asText("something"),
                        snapshot.toString());
            }

            String material = expected.path("material").asText(null);
            if (material != null
                    && !material.equalsIgnoreCase(actual.path("material").asText())) {
                return ScenarioResult.StepResult.failed(index, action,
                        "slot " + slot + " expected " + material.toUpperCase(java.util.Locale.ROOT)
                                + " but held " + actual.path("material").asText(),
                        snapshot.toString());
            }

            String name = expected.path("name").asText(null);
            if (name != null && !plain(actual.path("displayName").asText("")).contains(name)) {
                return ScenarioResult.StepResult.failed(index, action,
                        "slot " + slot + " expected a name containing '" + name + "' but it was '"
                                + actual.path("displayName").asText("") + "'",
                        snapshot.toString());
            }

            if (expected.hasNonNull("amount")
                    && actual.path("amount").asInt() != expected.path("amount").asInt()) {
                return ScenarioResult.StepResult.failed(index, action,
                        "slot " + slot + " expected " + expected.path("amount").asInt()
                                + " of them but found " + actual.path("amount").asInt(),
                        snapshot.toString());
            }

            String lore = expected.path("lore").asText(null);
            if (lore != null && !plain(actual.path("lore").toString()).contains(lore)) {
                return ScenarioResult.StepResult.failed(index, action,
                        "slot " + slot + " expected lore containing '" + lore + "' but it was "
                                + actual.path("lore"),
                        snapshot.toString());
            }

            String modelString = expected.path("modelDataString").asText(null);
            if (modelString != null) {
                JsonNode strings = actual.path("modelData").path("strings");
                boolean found = false;
                for (JsonNode candidate : strings) {
                    found = found || candidate.asText().equals(modelString);
                }
                if (!found) {
                    return ScenarioResult.StepResult.failed(index, action,
                            "slot " + slot + " expected model data string '" + modelString
                                    + "' but the item has " + (strings.isMissingNode()
                                            ? "no custom_model_data component" : strings.toString()),
                            snapshot.toString());
                }
            }

            if (expected.hasNonNull("customModelData")) {
                JsonNode found = actual.path("customModelData");
                if (found.isNull() || found.isMissingNode()
                        || found.asInt() != expected.path("customModelData").asInt()) {

                    return ScenarioResult.StepResult.failed(index, action,
                            "slot " + slot + " expected customModelData "
                                    + expected.path("customModelData").asInt() + " but "
                                    + (found.isNull() || found.isMissingNode()
                                            ? "the item has none" : "it was " + found.asInt()),
                            snapshot.toString());
                }
            }
        }

        return ScenarioResult.StepResult.ok(index, action,
                view + " '" + snapshot.path("title").asText("") + "', "
                        + checked + " slot(s) as expected");
    }

    /** The listed item at a slot, or {@code null} — empty slots are omitted from the snapshot. */
    private static JsonNode slotIn(JsonNode snapshot, int slot) {
        for (JsonNode item : snapshot.path("items")) {
            if (item.path("slot").asInt() == slot) {
                return item;
            }
        }
        return null;
    }

    /** Drops legacy colour codes. */
    private static String plain(String text) {
        return text == null ? "" : text.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }

    /** Copies a step's parameters into wait_for arguments, dropping the ones it does not take. */
    private ObjectNode waitArguments(JsonNode step) {
        ObjectNode arguments = AgentClient.arguments();
        step.fields().forEachRemaining(field -> {
            if (field.getKey().equals("action") || field.getKey().equals("bot")) {
                return;
            }
            arguments.set(field.getKey(), field.getValue());
        });
        if (!arguments.has("timeoutMillis")) {
            arguments.put("timeoutMillis", DEFAULT_WAIT.toMillis());
        }
        return arguments;
    }

    /** Runs an action against a named bot, turning a runner failure into a step failure. */
    private void act(JsonNode step, BotAction action) {
        String name = required(step, "bot");
        if (!bots.bots().contains(name)) {
            throw new IllegalStateException(
                    "no bot named " + name + " — spawn it before acting with it");
        }
        try {
            action.perform(new BotRunner.BotHandle(bots, name, 0, 0, 0));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(String.valueOf(e.getMessage()), e);
        }
    }

    @FunctionalInterface
    private interface BotAction {
        void perform(BotRunner.BotHandle bot) throws java.io.IOException;
    }

    private static String required(JsonNode step, String field) {
        String value = step.path(field).asText("");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("step needs '" + field + "'");
        }
        return value;
    }

    /** Where the event stream stands right now, so assertions can be scoped to this run. */
    private long currentEventSequence() {
        try {
            String cursor = agent.call("server_info", AgentClient.arguments())
                    .path("latestEventCursor").asText("");
            return cursor.contains(":")
                    ? Long.parseLong(cursor.substring(cursor.indexOf(':') + 1))
                    : 0;
        } catch (RuntimeException e) {

            return 0;
        }
    }

    /** Server state at the moment of an unexpected failure. */
    private String snapshot() {
        try {
            return "events=" + agent.call("events_summary", AgentClient.arguments()).path("counts");
        } catch (RuntimeException e) {
            return "(could not read server state: " + e.getMessage() + ")";
        }
    }
}
