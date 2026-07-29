package moe.vitamin.minecraft.mcp.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import moe.vitamin.minecraft.mcp.agent.core.AgentQueries;
import moe.vitamin.minecraft.mcp.agent.core.SequencedRingBuffer;
import moe.vitamin.minecraft.mcp.contract.Cursor;
import moe.vitamin.minecraft.mcp.contract.EventRecord;
import moe.vitamin.minecraft.mcp.contract.ExceptionGroup;
import moe.vitamin.minecraft.mcp.contract.LogEntry;
import moe.vitamin.minecraft.mcp.contract.LogLevel;
import moe.vitamin.minecraft.mcp.contract.ResponseBudget;
import moe.vitamin.minecraft.mcp.contract.Sequenced;

/**
 * The tools this agent exposes, and what they do.
 *
 * <p>Six tools rather than thirty. A large surface of narrow tools reads well in a list and
 * then goes unused, because the caller has to guess which one applies; parameters on a few
 * broad tools are easier to get right (CLAUDE.md, docs/design.md §10). {@code exceptions_recent}
 * taking an optional hash instead of gaining a sibling {@code exception_detail} tool is that
 * rule in practice.
 *
 * <p>There is no {@code logs_tail}. "The last N lines" sounds useful and is almost never what
 * was wanted — it burns the response budget on whatever happened most recently, which on a busy
 * server is join messages. A pattern search answers the actual question.
 */
final class AgentTools {

    private final AgentQueries capture;
    private final ObjectMapper mapper;
    private final ResponseBudget budget;
    private final boolean readOnly;

    /** Ceiling on a caller-supplied regex, so a pathological pattern cannot be huge as well as slow. */
    private static final int MAX_PATTERN_LENGTH = 500;

    AgentTools(AgentQueries capture, ObjectMapper mapper, ResponseBudget budget, boolean readOnly) {
        this.capture = capture;
        this.mapper = mapper;
        this.budget = budget;
        this.readOnly = readOnly;
    }

    // ------------------------------------------------------------- discovery

    ArrayNode listTools() {
        ArrayNode tools = mapper.createArrayNode();

        tools.add(tool("server_info",
                "Server implementation, version, TPS, online players, installed plugins, and "
                        + "capture statistics. Start here when you do not know what you are "
                        + "looking at.",
                schema -> {}));

        tools.add(tool("events_summary",
                "Counts captured events by type over a time window. ALWAYS call this before "
                        + "events_query: it is small regardless of how busy the server is, and "
                        + "it tells you which types are worth asking for in detail.",
                properties -> {
                    numberProperty(properties, "from", "Window start, epoch milliseconds. Omit for everything retained.");
                    numberProperty(properties, "to", "Window end, epoch milliseconds. Omit for 'up to now'.");
                }));

        tools.add(tool("events_query",
                "Reads individual captured events. High-frequency types (PlayerMoveEvent, "
                        + "BlockPhysicsEvent, ChunkLoadEvent, entity movement) are excluded "
                        + "unless you name them in 'types' explicitly. Page with 'cursor'.",
                properties -> {
                    arrayProperty(properties, "types",
                            "Event type simple names, e.g. ['BlockBreakEvent']. Naming a "
                                    + "high-frequency type is what opts into it.");
                    stringProperty(properties, "player", "Restrict to one player name.");
                    stringProperty(properties, "cursor", "Resume token from a previous response.");
                    numberProperty(properties, "limit",
                            "Maximum records, capped at " + budget.maxItems() + ".");
                }));

        tools.add(tool("logs_query",
                "Searches captured server logs by severity and regular expression. There is no "
                        + "'last N lines' tool; search for what you are looking for.",
                properties -> {
                    enumProperty(properties, "level", "Minimum severity.",
                            List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR"));
                    stringProperty(properties, "pattern",
                            "Java regular expression matched against the message.");
                    stringProperty(properties, "cursor", "Resume token from a previous response.");
                    numberProperty(properties, "limit",
                            "Maximum records, capped at " + budget.maxItems() + ".");
                }));

        tools.add(tool("exceptions_recent",
                "Distinct exceptions, most recently seen first, each collapsed with an "
                        + "occurrence count and when it was first seen. Stack traces are "
                        + "omitted; pass 'hash' to fetch one.",
                properties -> {
                    numberProperty(properties, "limit", "Maximum groups.");
                    stringProperty(properties, "hash",
                            "Fetch this one exception including its full stack trace.");
                }));

        tools.add(tool("state_query",
                "Reads current server state directly rather than inferring it from events. "
                        + "kind='player' needs 'target' (a player name) and optionally "
                        + "'permissions'; kind='block' needs 'x','y','z' and optionally 'world'.",
                properties -> {
                    enumProperty(properties, "kind", "What to read.", List.of("player", "block"));
                    stringProperty(properties, "target", "Player name, for kind='player'.");
                    arrayProperty(properties, "permissions",
                            "Permission nodes to test. They can only be tested, not listed.");
                    stringProperty(properties, "world", "World name, for kind='block'.");
                    numberProperty(properties, "x", "Block X, for kind='block'.");
                    numberProperty(properties, "y", "Block Y, for kind='block'.");
                    numberProperty(properties, "z", "Block Z, for kind='block'.");
                }));

        // Everything above only reads. The line below is the boundary: with read-only left on,
        // which is the default, there is no exposed way to change the server at all — not
        // restricted, absent. An operator has to opt in deliberately (docs/design.md §14).
        if (!readOnly) {
            tools.add(tool("command_exec",
                    "Runs a command on the server, as the console by default. This CHANGES the "
                            + "server. Returns whether a handler accepted it plus whatever it "
                            + "logged, which is usually where the real answer is — many commands "
                            + "report failure in their output while still succeeding formally.",
                    properties -> {
                        stringProperty(properties, "command",
                                "The command, with or without a leading slash.");
                        stringProperty(properties, "as",
                                "Player name to run as. Omit to run as the console.");
                    }));
        }

        return tools;
    }

    // ------------------------------------------------------------- execution

    /**
     * Runs one tool.
     *
     * @throws ToolException if the arguments are unusable or the tool does not exist
     */
    JsonNode call(String name, JsonNode arguments) {
        JsonNode args = arguments == null || arguments.isNull() ? mapper.createObjectNode() : arguments;

        return switch (name) {
            case "server_info" -> serverInfo();
            case "events_summary" -> eventsSummary(args);
            case "events_query" -> eventsQuery(args);
            case "logs_query" -> logsQuery(args);
            case "exceptions_recent" -> exceptionsRecent(args);
            case "state_query" -> stateQuery(args);
            case "command_exec" -> commandExec(args);
            default -> throw new ToolException("Unknown tool: " + name);
        };
    }

    private JsonNode serverInfo() {
        ObjectNode result = mapper.valueToTree(capture.serverInfo());
        result.set("capture", mapper.valueToTree(capture.captureStatus()));
        result.put("readOnly", readOnly);
        // Handed out so a caller can watch for what happens next without replaying history.
        result.put("latestEventCursor", capture.latestEventCursor());
        result.put("latestLogCursor", capture.latestLogCursor());
        return result;
    }

    private JsonNode eventsSummary(JsonNode args) {
        long from = args.path("from").asLong(0);
        long to = args.path("to").asLong(0);
        return mapper.valueToTree(capture.summarize(from, to));
    }

    private JsonNode eventsQuery(JsonNode args) {
        Set<String> types = stringSet(args.path("types"));
        String player = text(args.path("player"));
        String cursor = text(args.path("cursor"));
        int limit = budget.clampLimit(args.path("limit").asInt(0));

        SequencedRingBuffer.Batch<EventRecord> batch;
        try {
            batch = capture.queryEvents(cursor, types, player, limit);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
        return page(batch, Cursor.EVENTS);
    }

    private JsonNode logsQuery(JsonNode args) {
        LogLevel level = parseLevel(text(args.path("level")));
        Pattern pattern = parsePattern(text(args.path("pattern")));
        String cursor = text(args.path("cursor"));
        int limit = budget.clampLimit(args.path("limit").asInt(0));

        SequencedRingBuffer.Batch<LogEntry> batch;
        try {
            batch = capture.queryLogs(cursor, level, pattern, limit);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
        return page(batch, Cursor.LOGS);
    }

    private JsonNode exceptionsRecent(JsonNode args) {
        String hash = text(args.path("hash"));
        if (hash != null) {
            ExceptionGroup group = capture.exceptionByHash(hash);
            if (group == null) {
                throw new ToolException("No exception is recorded under hash: " + hash);
            }
            return mapper.valueToTree(group);
        }

        int limit = budget.clampLimit(args.path("limit").asInt(0));
        ObjectNode result = mapper.createObjectNode();
        result.set("items", mapper.valueToTree(capture.recentExceptions(limit)));
        return result;
    }

    private JsonNode stateQuery(JsonNode args) {
        String kind = text(args.path("kind"));
        if (kind == null) {
            throw new ToolException("state_query needs 'kind': 'player' or 'block'.");
        }

        return switch (kind.toLowerCase(java.util.Locale.ROOT)) {
            case "player" -> {
                String target = text(args.path("target"));
                if (target == null) {
                    throw new ToolException("state_query kind='player' needs 'target'.");
                }
                yield mapper.valueToTree(
                        capture.playerState(target, stringSet(args.path("permissions"))));
            }
            case "block" -> {
                ObjectNode result = mapper.createObjectNode();
                int x = args.path("x").asInt();
                int y = args.path("y").asInt();
                int z = args.path("z").asInt();
                String world = text(args.path("world"));
                String material = capture.blockAt(world, x, y, z);
                if (material == null) {
                    throw new ToolException("No such world: " + world);
                }
                result.put("world", world);
                result.put("x", x);
                result.put("y", y);
                result.put("z", z);
                result.put("block", material);
                yield result;
            }
            default -> throw new ToolException(
                    "Unknown kind '" + kind + "'. Expected 'player' or 'block'.");
        };
    }

    private JsonNode commandExec(JsonNode args) {
        // Reachable only when the tool was listed, but checked again here: a caller that
        // guessed the name must not get through just because it skipped tools/list.
        if (readOnly) {
            throw new ToolException(
                    "command_exec is unavailable: this agent is running read-only. Set "
                            + "'read-only: false' in config.yml to allow it.");
        }

        String command = text(args.path("command"));
        if (command == null) {
            throw new ToolException("command_exec needs 'command'.");
        }
        // Normalised here, at the boundary, so every caller is treated the same whether or not
        // it typed the slash a human would. Bukkit dispatches without one.
        String normalised = command.startsWith("/") ? command.substring(1) : command;
        if (normalised.isBlank()) {
            throw new ToolException("command_exec was given an empty command.");
        }

        try {
            return mapper.valueToTree(capture.executeCommand(
                    normalised, text(args.path("as")), java.time.Duration.ofSeconds(10)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ToolException(String.valueOf(e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- paging

    /**
     * Serializes a batch, stopping at whichever limit is reached first.
     *
     * <p>The byte ceiling is applied by measuring each record as it is added rather than
     * guessing from the count, because payload sizes vary by orders of magnitude between event
     * types. One record is always included even if it alone exceeds the budget — otherwise a
     * single oversized record would stall paging forever at the same cursor.
     */
    private <T extends Sequenced> ObjectNode page(SequencedRingBuffer.Batch<T> batch, String stream) {
        ArrayNode items = mapper.createArrayNode();
        List<T> source = batch.items();

        int consumedBytes = 0;
        int included = 0;
        for (T record : source) {
            JsonNode node = mapper.valueToTree(record);
            int size = node.toString().length();
            if (included > 0 && consumedBytes + size > budget.maxBytes()) {
                break;
            }
            items.add(node);
            consumedBytes += size;
            included++;
        }

        boolean cutByBytes = included < source.size();
        boolean truncated = cutByBytes || !batch.exhausted();

        String nextCursor = null;
        if (cutByBytes) {
            nextCursor = new Cursor(stream, source.get(included - 1).sequence() + 1).encode();
        } else if (!batch.exhausted()) {
            nextCursor = new Cursor(stream, batch.nextSequence()).encode();
        }

        ObjectNode result = mapper.createObjectNode();
        result.set("items", items);
        result.put("nextCursor", nextCursor);
        result.put("truncated", truncated);
        // Separate from truncation on purpose: truncated means "page again", dropped means
        // "these records no longer exist anywhere".
        result.put("dropped", batch.dropped());
        return result;
    }

    // ------------------------------------------------------------- arguments

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static Set<String> stringSet(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        Set<String> values = new LinkedHashSet<>();
        node.forEach(element -> {
            String value = text(element);
            if (value != null) {
                values.add(value);
            }
        });
        return values.isEmpty() ? null : values;
    }

    private static LogLevel parseLevel(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LogLevel.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ToolException("Unknown log level '" + raw
                    + "'. Expected one of TRACE, DEBUG, INFO, WARN, ERROR.");
        }
    }

    private static Pattern parsePattern(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.length() > MAX_PATTERN_LENGTH) {
            throw new ToolException("Pattern is longer than " + MAX_PATTERN_LENGTH + " characters.");
        }
        try {
            return Pattern.compile(raw);
        } catch (PatternSyntaxException e) {
            throw new ToolException("Invalid regular expression: " + e.getDescription());
        }
    }

    // ---------------------------------------------------------------- schema

    private ObjectNode tool(String name, String description, java.util.function.Consumer<ObjectNode> properties) {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);

        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        properties.accept(props);
        schema.set("required", mapper.createArrayNode());
        return tool;
    }

    private static void stringProperty(ObjectNode properties, String name, String description) {
        ObjectNode property = properties.putObject(name);
        property.put("type", "string");
        property.put("description", description);
    }

    private static void numberProperty(ObjectNode properties, String name, String description) {
        ObjectNode property = properties.putObject(name);
        property.put("type", "integer");
        property.put("description", description);
    }

    private static void arrayProperty(ObjectNode properties, String name, String description) {
        ObjectNode property = properties.putObject(name);
        property.put("type", "array");
        property.put("description", description);
        property.putObject("items").put("type", "string");
    }

    private void enumProperty(ObjectNode properties, String name, String description, List<String> values) {
        ObjectNode property = properties.putObject(name);
        property.put("type", "string");
        property.put("description", description);
        ArrayNode allowed = property.putArray("enum");
        new ArrayList<>(values).forEach(allowed::add);
    }

    /** A tool-level failure: reported back to the caller as content, not as a transport error. */
    static final class ToolException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ToolException(String message) {
            super(message);
        }
    }
}
