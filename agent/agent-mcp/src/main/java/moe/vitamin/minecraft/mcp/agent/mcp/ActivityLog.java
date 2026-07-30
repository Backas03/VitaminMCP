package moe.vitamin.minecraft.mcp.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import moe.vitamin.minecraft.mcp.agent.core.ActivityLogging;

/**
 * Writes what the agent is asked to do, and what it answered, to the server console.
 *
 * <p>Without this the agent is the one thing on a server that acts without leaving a trace:
 * events and logs are captured into buffers only an MCP client can read, so an operator
 * watching their own console sees the plugin load and then nothing, however much is happening.
 * That is the wrong default for software that can run console commands.
 *
 * <p>Each call produces two lines, on arrival and on completion. The pair is what makes a call
 * that never finishes visible — {@code wait_for} can hold a request open for a minute, and a
 * single line written afterwards would show nothing at all while it was happening. The
 * {@code #n} identifier is there because four request threads interleave.
 *
 * <p>Both the arguments and the answer are truncated. The response budget allows 50KB, and a
 * console is not where anyone wants to read that; the first few hundred characters say which
 * call this was and roughly what came back, which is what the line is for. A client that needs
 * the whole payload already has it.
 *
 * <p>{@link ActivityLogging#OFF} silences ordinary calls but not refused tokens or
 * state-changing tools. Those are the records that matter after the fact, and an operator
 * turning down console noise is not asking to give them up.
 */
final class ActivityLog {

    /** Longest argument blob written to the console before it is cut. */
    private static final int MAX_ARGUMENT_CHARS = 300;

    /** Longest answer written on a completion line. */
    private static final int MAX_ANSWER_CHARS = 400;

    private final Logger logger;
    private final ActivityLogging verbosity;
    private final AtomicLong nextId = new AtomicLong();

    ActivityLog(Logger logger, ActivityLogging verbosity) {
        this.logger = logger;
        this.verbosity = verbosity;
    }

    // ------------------------------------------------------------------ auth

    /**
     * A request that failed authentication.
     *
     * <p>Always logged, and at WARNING: on a loopback endpoint this means something local is
     * misconfigured, and on an exposed one it means someone is trying tokens. Neither is
     * something to find out about only from a client's error message. The token itself is never
     * written — a rejected guess is still a secret to whoever it belongs to.
     */
    void refused(String client, String reason) {
        logger.warning("MCP request from " + client + " rejected: " + reason);
    }

    // -------------------------------------------------------------- requests

    /** A request that never reached dispatch: malformed, oversized, or the wrong HTTP verb. */
    void malformed(String client, String problem) {
        if (!verbosity.logsCalls()) {
            return;
        }
        logger.info("MCP request from " + client + " not understood: " + problem);
    }

    /** A notification, which gets no reply and so has no completion line. */
    void notification(String client, String method) {
        if (!verbosity.logsCalls()) {
            return;
        }
        logger.info("MCP #" + nextId.incrementAndGet() + " " + client + " " + method
                + " (notification)");
    }

    /**
     * Records the arrival of a call and returns the handle that records its answer.
     *
     * @param toolName  the tool being called, or {@code null} for a protocol method
     * @param arguments what the caller sent, or {@code null}
     * @param audited   whether this call must be logged even with activity logging turned off
     */
    Call begin(String client, String method, String toolName, JsonNode arguments, boolean audited) {
        // An audited call carries its detail whatever the verbosity: "command_exec" without the
        // command is not a record of anything.
        boolean withDetail = verbosity.logsArguments() || audited;

        StringBuilder description = new StringBuilder(method);
        if (toolName != null && !toolName.isEmpty()) {
            description.append(' ').append(toolName);
        }
        if (withDetail && arguments != null && !arguments.isEmpty()) {
            description.append(' ').append(truncate(arguments.toString(), MAX_ARGUMENT_CHARS));
        }

        boolean announce = verbosity.logsCalls() || audited;
        long id = nextId.incrementAndGet();
        if (announce) {
            logger.info("MCP #" + id + " " + client + " " + description);
        }
        return new Call(id, description.toString(), announce, withDetail);
    }

    /** One in-flight call, from arrival to answer. */
    final class Call {

        private final long id;
        private final String description;
        private final boolean announced;
        private final boolean withDetail;
        private final long startedAt = System.nanoTime();

        private Call(long id, String description, boolean announced, boolean withDetail) {
            this.id = id;
            this.description = description;
            this.announced = announced;
            this.withDetail = withDetail;
        }

        /**
         * @param answer what the caller was sent back, dropped from the line under
         *               {@link ActivityLogging#SUMMARY} for the same reason arguments are
         */
        void succeeded(String answer) {
            boolean show = withDetail && answer != null && !answer.isEmpty();
            finish("ok", show ? answer : null);
        }

        /**
         * A call that came back with a failure the caller can read.
         *
         * <p>Logged even when the arrival was not, because a failure is the case an operator
         * goes looking for. It repeats the description so a lone completion line still says
         * which call it belonged to.
         */
        void failed(String reason) {
            finish("failed", reason);
        }

        private void finish(String outcome, String detail) {
            long millis = (System.nanoTime() - startedAt) / 1_000_000;
            String suffix = detail == null
                    ? ""
                    : " -> " + truncate(oneLine(detail), MAX_ANSWER_CHARS);

            if (announced) {
                logger.info("MCP #" + id + " " + outcome + " in " + millis + "ms" + suffix);
            } else if ("failed".equals(outcome)) {
                logger.info("MCP #" + id + " " + description + " " + outcome + " in " + millis
                        + "ms" + suffix);
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private static String truncate(String value, int limit) {
        return value.length() <= limit
                ? value
                : value.substring(0, limit) + "... (" + value.length() + " chars total)";
    }

    /**
     * Keeps one console line to one line.
     *
     * <p>Tool payloads are pretty-printed for the client that reads them, which on a console is
     * a wall of indentation. Collapsing whitespace is what makes the answer legible next to the
     * request it belongs to.
     */
    private static String oneLine(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
