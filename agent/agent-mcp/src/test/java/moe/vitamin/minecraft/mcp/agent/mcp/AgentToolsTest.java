package moe.vitamin.minecraft.mcp.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import moe.vitamin.minecraft.mcp.agent.core.AgentQueries;
import moe.vitamin.minecraft.mcp.agent.core.SequencedRingBuffer;
import moe.vitamin.minecraft.mcp.contract.EventRecord;
import moe.vitamin.minecraft.mcp.contract.EventsSummary;
import moe.vitamin.minecraft.mcp.contract.ExceptionGroup;
import moe.vitamin.minecraft.mcp.contract.LogEntry;
import moe.vitamin.minecraft.mcp.contract.LogLevel;
import moe.vitamin.minecraft.mcp.contract.ResponseBudget;
import moe.vitamin.minecraft.mcp.contract.ServerInfo;
import org.junit.jupiter.api.Test;

class AgentToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FakeQueries queries = new FakeQueries();

    private AgentTools toolsWith(ResponseBudget budget) {
        return new AgentTools(queries, mapper, budget, true);
    }

    private AgentTools tools() {
        return toolsWith(ResponseBudget.DEFAULT);
    }

    private ObjectNode args(String json) {
        try {
            return (ObjectNode) mapper.readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static EventRecord event(long sequence, String type, String payload) {
        return new EventRecord(sequence, 1_000L + sequence, type, "Alice", false,
                Map.of("detail", payload));
    }

    // ------------------------------------------------------------- discovery

    @Test
    void exposesTheToolsTheDesignSpecifies() {
        ArrayNode listed = tools().listTools();

        List<String> names = StreamSupport.stream(listed.spliterator(), false)
                .map(tool -> tool.get("name").asText())
                .toList();

        assertEquals(
                List.of("server_info", "events_summary", "events_query", "logs_query",
                        "exceptions_recent", "state_query", "wait_for"),
                names);
        // command_exec is absent, not merely restricted — see writeToolsAreAbsentWhenReadOnly.
        assertFalse(names.contains("command_exec"));
    }

    @Test
    void everyToolDeclaresAnObjectInputSchema() {
        for (JsonNode tool : tools().listTools()) {
            JsonNode schema = tool.get("inputSchema");
            assertNotNull(schema, tool.get("name").asText());
            assertEquals("object", schema.get("type").asText());
            assertNotNull(schema.get("properties"));
        }
    }

    @Test
    void writeToolsAreAbsentWhenReadOnly() {
        List<String> readOnlyNames = names(toolsWith(ResponseBudget.DEFAULT).listTools());
        List<String> writableNames =
                names(new AgentTools(queries, mapper, ResponseBudget.DEFAULT, false).listTools());

        // The security boundary is that a default install does not expose a way to change the
        // server at all. Not "exposes it and refuses" — absent from the listing entirely.
        assertFalse(readOnlyNames.contains("command_exec"));
        assertTrue(writableNames.contains("command_exec"));
    }

    @Test
    void commandExecRefusesEvenIfCalledDirectlyWhileReadOnly() {
        // A caller that skipped tools/list and guessed the name must not get through.
        AgentTools.ToolException thrown = assertThrows(AgentTools.ToolException.class,
                () -> tools().call("command_exec", args("{\"command\":\"op Someone\"}")));

        assertTrue(thrown.getMessage().contains("read-only"));
    }

    @Test
    void commandExecPassesTheCommandThroughWhenAllowed() {
        AgentTools writable = new AgentTools(queries, mapper, ResponseBudget.DEFAULT, false);

        writable.call("command_exec", args("{\"command\":\"/op Tester1\",\"as\":\"Admin\"}"));

        // The leading slash is stripped: Bukkit dispatches without it.
        assertEquals("op Tester1", queries.lastCommand);
        assertEquals("Admin", queries.lastCommandAs);
    }

    @Test
    void stateQueryNeedsAKind() {
        assertThrows(AgentTools.ToolException.class, () -> tools().call("state_query", args("{}")));
        assertThrows(AgentTools.ToolException.class,
                () -> tools().call("state_query", args("{\"kind\":\"weather\"}")));
    }

    @Test
    void stateQueryReadsAPlayerAndABlock() {
        JsonNode player = tools().call("state_query",
                args("{\"kind\":\"player\",\"target\":\"Tester1\"}"));
        assertEquals("CREATIVE", player.get("gameMode").asText());

        JsonNode block = tools().call("state_query",
                args("{\"kind\":\"block\",\"world\":\"world\",\"x\":1,\"y\":2,\"z\":3}"));
        assertEquals("STONE", block.get("block").asText());
    }

    private static List<String> names(ArrayNode listed) {
        return StreamSupport.stream(listed.spliterator(), false)
                .map(tool -> tool.get("name").asText())
                .toList();
    }

    @Test
    void readOnlyIsReportedSoACallerKnowsWhyWriteToolsAreMissing() {
        JsonNode info = tools().call("server_info", null);

        assertTrue(info.get("readOnly").asBoolean());
    }

    // ---------------------------------------------------------------- paging

    @Test
    void aPageThatFitsIsNotTruncatedAndEndsTheStream() {
        queries.events = new SequencedRingBuffer.Batch<>(
                List.of(event(0, "BlockBreakEvent", "a"), event(1, "BlockBreakEvent", "b")),
                2, 0, true);

        JsonNode page = tools().call("events_query", args("{}"));

        assertEquals(2, page.get("items").size());
        assertFalse(page.get("truncated").asBoolean());
        assertTrue(page.get("nextCursor").isNull());
        assertEquals(0, page.get("dropped").asLong());
    }

    @Test
    void theByteBudgetCutsThePageAndHandsBackAResumeCursor() {
        String bulky = "x".repeat(200);
        queries.events = new SequencedRingBuffer.Batch<>(
                List.of(event(10, "E", bulky), event(11, "E", bulky), event(12, "E", bulky)),
                13, 0, true);

        JsonNode page = toolsWith(new ResponseBudget(200, 300)).call("events_query", args("{}"));

        // Cut by bytes even though the item count and the batch itself were both fine.
        assertEquals(1, page.get("items").size());
        assertTrue(page.get("truncated").asBoolean());
        assertEquals("events:11", page.get("nextCursor").asText());
    }

    @Test
    void oversizedRecordsStillMakeProgress() {
        String enormous = "x".repeat(5_000);
        queries.events = new SequencedRingBuffer.Batch<>(
                List.of(event(0, "E", enormous), event(1, "E", enormous)), 2, 0, true);

        // A single record larger than the whole budget must still be returned, otherwise paging
        // stalls on the same cursor forever and the caller can never get past it.
        JsonNode page = toolsWith(new ResponseBudget(200, 100)).call("events_query", args("{}"));

        assertEquals(1, page.get("items").size());
        assertEquals("events:1", page.get("nextCursor").asText());
    }

    @Test
    void anUnexhaustedBatchPagesFromTheBatchSequence() {
        queries.events = new SequencedRingBuffer.Batch<>(
                List.of(event(0, "E", "a")), 7, 0, /* exhausted = */ false);

        JsonNode page = tools().call("events_query", args("{}"));

        assertTrue(page.get("truncated").asBoolean());
        assertEquals("events:7", page.get("nextCursor").asText());
    }

    @Test
    void dropsAreReportedSeparatelyFromTruncation() {
        queries.events = new SequencedRingBuffer.Batch<>(
                List.of(event(50, "E", "a")), 51, 49, true);

        JsonNode page = tools().call("events_query", args("{}"));

        // Nothing was held back, but 49 records are gone for good. Conflating the two would let
        // a reader treat permanent loss as a paging boundary.
        assertFalse(page.get("truncated").asBoolean());
        assertEquals(49, page.get("dropped").asLong());
    }

    @Test
    void limitIsClampedToTheBudget() {
        tools().call("events_query", args("{\"limit\": 100000}"));

        assertEquals(ResponseBudget.DEFAULT.maxItems(), queries.lastLimit);
    }

    @Test
    void requestedTypesAndPlayerReachTheQuery() {
        tools().call("events_query", args("{\"types\":[\"PlayerMoveEvent\"],\"player\":\"Bob\"}"));

        assertEquals(List.of("PlayerMoveEvent"), new ArrayList<>(queries.lastTypes));
        assertEquals("Bob", queries.lastPlayer);
    }

    // ------------------------------------------------------------- arguments

    @Test
    void rejectsAnUnknownLogLevel() {
        AgentTools.ToolException thrown = assertThrows(AgentTools.ToolException.class,
                () -> tools().call("logs_query", args("{\"level\":\"LOUD\"}")));

        assertTrue(thrown.getMessage().contains("TRACE"));
    }

    @Test
    void rejectsAnInvalidRegularExpression() {
        assertThrows(AgentTools.ToolException.class,
                () -> tools().call("logs_query", args("{\"pattern\":\"[unclosed\"}")));
    }

    @Test
    void rejectsAnOverlongPattern() {
        String pattern = "a".repeat(1_000);

        assertThrows(AgentTools.ToolException.class,
                () -> tools().call("logs_query", args("{\"pattern\":\"" + pattern + "\"}")));
    }

    @Test
    void acceptsAValidLevelAndPattern() {
        tools().call("logs_query", args("{\"level\":\"warn\",\"pattern\":\"time.?out\"}"));

        assertEquals(LogLevel.WARN, queries.lastLevel);
        assertEquals("time.?out", queries.lastPattern.pattern());
    }

    @Test
    void blankArgumentsAreTreatedAsAbsent() {
        tools().call("events_query", args("{\"player\":\"  \",\"cursor\":\"\"}"));

        // An empty string from a caller means "I did not filter", not "match the empty name".
        assertEquals(null, queries.lastPlayer);
        assertEquals(null, queries.lastCursor);
    }

    // ------------------------------------------------------------ exceptions

    @Test
    void fetchingAnUnknownExceptionHashIsAToolError() {
        AgentTools.ToolException thrown = assertThrows(AgentTools.ToolException.class,
                () -> tools().call("exceptions_recent", args("{\"hash\":\"deadbeef\"}")));

        assertTrue(thrown.getMessage().contains("deadbeef"));
    }

    @Test
    void aKnownHashReturnsTheGroupWithItsStackTrace() {
        queries.exception = new ExceptionGroup(
                "abc", "java.lang.IllegalStateException", "boom", 12, 1L, 2L, "trace here");

        JsonNode result = tools().call("exceptions_recent", args("{\"hash\":\"abc\"}"));

        assertEquals(12, result.get("count").asLong());
        assertEquals("trace here", result.get("stackTrace").asText());
    }

    @Test
    void rejectsAnUnknownTool() {
        assertThrows(AgentTools.ToolException.class, () -> tools().call("drop_database", null));
    }

    // ------------------------------------------------------------------ fake

    /** Records what it was asked and returns whatever the test set up. */
    private static final class FakeQueries implements AgentQueries {

        SequencedRingBuffer.Batch<EventRecord> events =
                new SequencedRingBuffer.Batch<>(List.of(), 0, 0, true);
        SequencedRingBuffer.Batch<LogEntry> logs =
                new SequencedRingBuffer.Batch<>(List.of(), 0, 0, true);
        ExceptionGroup exception;

        int lastLimit;
        Collection<String> lastTypes = List.of();
        String lastPlayer;
        String lastCursor;
        LogLevel lastLevel;
        Pattern lastPattern;

        @Override
        public EventsSummary summarize(long from, long to) {
            return new EventsSummary(from, to, 0, 0, List.of());
        }

        @Override
        public SequencedRingBuffer.Batch<EventRecord> queryEvents(
                String cursorToken, Collection<String> types, String player, int limit) {
            this.lastCursor = cursorToken;
            this.lastTypes = types == null ? List.of() : types;
            this.lastPlayer = player;
            this.lastLimit = limit;
            return events;
        }

        @Override
        public SequencedRingBuffer.Batch<LogEntry> queryLogs(
                String cursorToken, LogLevel minLevel, Pattern pattern, int limit) {
            this.lastCursor = cursorToken;
            this.lastLevel = minLevel;
            this.lastPattern = pattern;
            this.lastLimit = limit;
            return logs;
        }

        @Override
        public List<ExceptionGroup> recentExceptions(int limit) {
            this.lastLimit = limit;
            return exception == null ? List.of() : List.of(exception.withoutStackTrace());
        }

        @Override
        public ExceptionGroup exceptionByHash(String hash) {
            return exception != null && exception.hash().equals(hash) ? exception : null;
        }

        @Override
        public ServerInfo serverInfo() {
            return new ServerInfo("Paper", "1.20.4-test", "1.20.4", List.of(20.0, 20.0, 19.9),
                    0, 20, 1_000L, List.of());
        }

        @Override
        public Map<String, Object> captureStatus() {
            return Map.of("eventsCaptured", 0L);
        }

        @Override
        public String latestEventCursor() {
            return "events:0";
        }

        @Override
        public String latestLogCursor() {
            return "logs:0";
        }

        String lastCommand;
        String lastCommandAs;

        @Override
        public moe.vitamin.minecraft.mcp.contract.PlayerState playerState(
                String name, Collection<String> permissionNodes) {
            return new moe.vitamin.minecraft.mcp.contract.PlayerState(
                    name, "00000000-0000-0000-0000-000000000000", true, "127.0.0.1", "CREATIVE",
                    false, "world", 1, 2, 3, List.of());
        }

        /** A one-button menu, enough to check the tool's shape without a server. */
        @Override
        public moe.vitamin.minecraft.mcp.contract.InventorySnapshot inventory(
                String name, boolean openMenu, int limit) {
            if (!openMenu) {
                return new moe.vitamin.minecraft.mcp.contract.InventorySnapshot(
                        "PLAYER", null, 36, 0, List.of(), false);
            }
            return new moe.vitamin.minecraft.mcp.contract.InventorySnapshot(
                    "CHEST", "§aShop", 27, 1,
                    List.of(new moe.vitamin.minecraft.mcp.contract.InventorySnapshot.Item(
                            11, "EMERALD", 1, "§aBuy", List.of("§7Click me"), false, 7, null)),
                    false);
        }


        @Override
        public moe.vitamin.minecraft.mcp.contract.WaitResult waitFor(
                moe.vitamin.minecraft.mcp.contract.WaitCondition condition,
                java.time.Duration timeout) {
            return moe.vitamin.minecraft.mcp.contract.WaitResult.matched(
                    condition.describe(), 5L, 1);
        }
        @Override
        public String blockAt(String world, int x, int y, int z) {
            return "world".equals(world) || world == null ? "STONE" : null;
        }

        @Override
        public moe.vitamin.minecraft.mcp.contract.CommandResult executeCommand(
                String command, String asPlayer, java.time.Duration timeout) {
            this.lastCommand = command;
            this.lastCommandAs = asPlayer;
            return new moe.vitamin.minecraft.mcp.contract.CommandResult(
                    command,
                    asPlayer == null
                            ? moe.vitamin.minecraft.mcp.contract.CommandResult.CONSOLE
                            : asPlayer,
                    true, List.of("ok"), 1L);
        }
    }
}
