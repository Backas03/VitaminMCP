package moe.vitamin.minecraft.mcp.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import moe.vitamin.minecraft.mcp.bot.core.BotRunner;

/**
 * Runs a declarative scenario against a server.
 *
 * <p>Scenarios are JSON rather than code because the main author is a language model, and a
 * list of steps is both easy to produce and easy to report against: a failure names the step
 * that failed, in the same form it was written (docs/design.md §11).
 *
 * <pre>
 * [
 *   {"action": "spawn",       "bot": "Tester1"},
 *   {"action": "break_block", "bot": "Tester1", "x": 10, "y": 63, "z": 20},
 *   {"action": "wait_for",    "condition": "block_is", "x": 10, "y": 63, "z": 20,
 *                             "material": "AIR"},
 *   {"action": "assert_event","eventType": "BlockBreakEvent", "player": "Tester1"}
 * ]
 * </pre>
 *
 * <p><b>There is no sleep step, and there will not be one.</b> Offering it guarantees it gets
 * used — it is always the shortest path past a timing problem — and every use is a scenario
 * calibrated to the machine that wrote it. {@code wait_for} covers the same ground by naming
 * the thing being waited for, which is both more reliable and more readable
 * (docs/roadmap.md Stage 3).
 */
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

        // Captured before the first step so assert_event can ask "did this happen during this
        // scenario", which is what a reader means by it. A wait started at step N only sees
        // events after step N, and the thing being asserted usually happened at step N-1.
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
                // Evidence is gathered here rather than at each call site so that every failure
                // arrives with the same context, including the ones nobody anticipated.
                results.add(ScenarioResult.StepResult.failed(
                        index, action, String.valueOf(e.getMessage()), snapshot()));
                return new ScenarioResult(false, results);
            }

            // Stop at the first failure. Continuing past one runs later steps against a state
            // the scenario never described, and their failures say nothing.
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
                    // Optional: without it the bot reports the address it really connects from,
                    // which is what a test should normally want.
                    BotRunner.BotHandle bot = bots.spawn(
                            name, step.hasNonNull("clientIp") ? step.get("clientIp").asText() : null);
                    yield ScenarioResult.StepResult.ok(index, action,
                            name + " joined at " + bot.x() + ", " + bot.y() + ", " + bot.z());
                } catch (java.io.IOException e) {
                    // The runner passes the server's own words through, so a protocol mismatch
                    // reads as "Outdated client!" rather than as a timeout.
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
                // Waits rather than reads once. These fields change asynchronously — /op has to
                // resolve a name to a UUID before it takes effect — so an immediate check races
                // the command that preceded it and fails for reasons unrelated to the test.
                ObjectNode arguments = waitArguments(step);
                arguments.put("condition", "player_state");
                arguments.put("name", required(step, "bot"));

                JsonNode result = agent.call("wait_for", arguments);
                if (result.path("matched").asBoolean()) {
                    yield ScenarioResult.StepResult.ok(index, action, "as expected");
                }

                // Report what it actually is, not merely that it was not what was wanted.
                ObjectNode query = AgentClient.arguments();
                query.put("kind", "player");
                query.put("target", required(step, "bot"));
                JsonNode actual = agent.call("state_query", query);
                yield ScenarioResult.StepResult.failed(index, action,
                        "player state never matched", actual.toString());
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
            // Falling back to 0 widens assert_event to everything retained, which is looser
            // than intended but never wrongly fails a scenario.
            return 0;
        }
    }

    /** Server state at the moment of an unexpected failure. Best effort — never masks the cause. */
    private String snapshot() {
        try {
            return "events=" + agent.call("events_summary", AgentClient.arguments()).path("counts");
        } catch (RuntimeException e) {
            return "(could not read server state: " + e.getMessage() + ")";
        }
    }
}
