package moe.vitamin.minecraft.mcp.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import moe.vitamin.minecraft.mcp.agent.core.ActivityLogging;

/** Writes what the agent is asked to do, and what it answered, to the server console. */
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

    /** A request that failed authentication. */
    void refused(String client, String reason) {
        logger.warning("MCP request from " + client + " rejected: " + reason);
    }

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

    /** Records the arrival of a call and returns the handle that records its answer. */
    Call begin(String client, String method, String toolName, JsonNode arguments, boolean audited) {

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

        void succeeded(String answer) {
            boolean show = withDetail && answer != null && !answer.isEmpty();
            finish("ok", show ? answer : null);
        }

        /** A call that came back with a failure the caller can read. */
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

    private static String truncate(String value, int limit) {
        return value.length() <= limit
                ? value
                : value.substring(0, limit) + "... (" + value.length() + " chars total)";
    }

    /** Keeps one console line to one line. */
    private static String oneLine(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
