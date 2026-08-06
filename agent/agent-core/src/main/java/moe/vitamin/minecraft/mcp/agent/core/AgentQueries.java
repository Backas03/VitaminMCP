package moe.vitamin.minecraft.mcp.agent.core;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import moe.vitamin.minecraft.mcp.contract.CommandResult;
import moe.vitamin.minecraft.mcp.contract.EventRecord;
import moe.vitamin.minecraft.mcp.contract.EventsSummary;
import moe.vitamin.minecraft.mcp.contract.ExceptionGroup;
import moe.vitamin.minecraft.mcp.contract.InventorySnapshot;
import moe.vitamin.minecraft.mcp.contract.LogEntry;
import moe.vitamin.minecraft.mcp.contract.LogLevel;
import moe.vitamin.minecraft.mcp.contract.PlayerState;
import moe.vitamin.minecraft.mcp.contract.ServerInfo;

/** Everything the MCP tools can ask the agent for. */
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

    /** What the server believes about a player. */
    PlayerState playerState(String name, java.util.Collection<String> permissionNodes);

    /** The material at a block position, or {@code null} if the world is unknown. */
    String blockAt(String world, int x, int y, int z);

    /** What a player has in front of them, or {@code null} if they are not online. */
    InventorySnapshot inventory(String name, boolean openMenu, int limit);

    /** Runs a command on the server. */
    CommandResult executeCommand(String command, String asPlayer, java.time.Duration timeout);

    /** Blocks until a condition holds, or the timeout elapses. */
    moe.vitamin.minecraft.mcp.contract.WaitResult waitFor(
            moe.vitamin.minecraft.mcp.contract.WaitCondition condition, java.time.Duration timeout);
}
