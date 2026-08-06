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

/**
 * Everything the MCP tools can ask the agent for.
 *
 * <p>This exists so the tool layer can be exercised without a running server. {@link
 * CaptureService} is the real implementation and needs a Bukkit {@code Plugin}; the paging,
 * budget and argument-handling logic that sits above it has no business requiring one
 * (CONTRIBUTING.md).
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

    /**
     * What the server believes about a player.
     *
     * @param permissionNodes nodes to test, since permissions can only be asked about one at a
     *                        time rather than listed
     */
    PlayerState playerState(String name, java.util.Collection<String> permissionNodes);

    /**
     * The material at a block position, or {@code null} if the world is unknown.
     *
     * <p>Exists because "did that block actually change" is otherwise only answerable by
     * inference from events, and the most common cause of a bot appearing to do nothing is that
     * it acted on a position holding something other than what the test assumed.
     */
    String blockAt(String world, int x, int y, int z);

    /**
     * What a player has in front of them, or {@code null} if they are not online.
     *
     * <p>The only way to check a plugin menu. Its contents live in a virtual inventory attached
     * to the open view and nowhere else — not in the player's NBT, not in any event payload — so
     * a test that wants to know whether the menu rendered correctly has to read it here.
     *
     * @param name      player to look at
     * @param openMenu  {@code true} for the menu they have open, {@code false} for their own
     *                  inventory. Asking for the menu when none is open reports the player's
     *                  own screen, which {@link InventorySnapshot#menuIsOpen()} distinguishes
     * @param limit     most slots to list, oldest-first by slot index
     */
    InventorySnapshot inventory(String name, boolean openMenu, int limit);

    /**
     * Runs a command on the server.
     *
     * <p>Separate from the read-only surface on purpose: exposing this is what turns the agent
     * from an observer into something that can change the server, and it stays unexposed unless
     * an operator opts in (docs/design.md §14).
     *
     * @param asPlayer player to run as, or {@code null} for the console
     */
    CommandResult executeCommand(String command, String asPlayer, java.time.Duration timeout);

    /**
     * Blocks until a condition holds, or the timeout elapses.
     *
     * <p>The alternative every caller reaches for otherwise is sleeping, which is a guess about
     * timing that is right on one machine and wrong on another — the mechanism that produces
     * flaky tests (docs/roadmap.md Stage 3).
     */
    moe.vitamin.minecraft.mcp.contract.WaitResult waitFor(
            moe.vitamin.minecraft.mcp.contract.WaitCondition condition, java.time.Duration timeout);
}
