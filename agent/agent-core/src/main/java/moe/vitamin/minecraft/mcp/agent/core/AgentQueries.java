package moe.vitamin.minecraft.mcp.agent.core;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import moe.vitamin.minecraft.mcp.contract.EventRecord;
import moe.vitamin.minecraft.mcp.contract.EventsSummary;
import moe.vitamin.minecraft.mcp.contract.ExceptionGroup;
import moe.vitamin.minecraft.mcp.contract.LogEntry;
import moe.vitamin.minecraft.mcp.contract.LogLevel;
import moe.vitamin.minecraft.mcp.contract.ServerInfo;

/**
 * Everything the MCP tools can ask the agent for.
 *
 * <p>This exists so the tool layer can be exercised without a running server. {@link
 * CaptureService} is the real implementation and needs a Bukkit {@code Plugin}; the paging,
 * budget and argument-handling logic that sits above it has no business requiring one
 * (CLAUDE.md).
 */
public interface AgentQueries {

    EventsSummary summarize(long from, long to);

    SequencedRingBuffer.Batch<EventRecord> queryEvents(
            String cursorToken, Collection<String> types, String player, int limit);

    SequencedRingBuffer.Batch<LogEntry> queryLogs(
            String cursorToken, LogLevel minLevel, Pattern pattern, int limit);

    List<ExceptionGroup> recentExceptions(int limit);

    ExceptionGroup exceptionByHash(String hash);

    ServerInfo serverInfo();

    /** Diagnostics about capture itself: buffer occupancy, drop counts, whether logs attached. */
    Map<String, Object> captureStatus();

    /** Cursor just past the newest event, for callers that only want what happens next. */
    String latestEventCursor();

    /** Cursor just past the newest log entry. */
    String latestLogCursor();
}
