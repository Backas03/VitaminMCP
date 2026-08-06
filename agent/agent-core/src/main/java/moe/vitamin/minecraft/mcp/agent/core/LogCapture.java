package moe.vitamin.minecraft.mcp.agent.core;

import java.util.logging.Level;
import moe.vitamin.minecraft.mcp.contract.LogEntry;
import moe.vitamin.minecraft.mcp.contract.LogLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;

/** Captures server logs by attaching a log4j2 appender. */
public final class LogCapture {

    private static final String APPENDER_NAME = "VitaminMCP";

    private final SequencedRingBuffer<LogEntry> buffer;
    private final ExceptionRegistry exceptions;
    private final java.util.logging.Logger pluginLogger;

    private CapturingAppender appender;
    private Logger rootLogger;

    public LogCapture(
            SequencedRingBuffer<LogEntry> buffer,
            ExceptionRegistry exceptions,
            java.util.logging.Logger pluginLogger) {
        this.buffer = buffer;
        this.exceptions = exceptions;
        this.pluginLogger = pluginLogger;
    }

    /** Attaches the appender to the root logger. */
    public boolean attach() {
        if (appender != null) {
            throw new IllegalStateException("Log capture is already attached");
        }
        try {
            org.apache.logging.log4j.Logger root = LogManager.getRootLogger();
            if (!(root instanceof Logger coreLogger)) {
                pluginLogger.warning("Root logger is not a log4j2 core logger ("
                        + root.getClass().getName() + "); log capture is disabled");
                return false;
            }

            CapturingAppender created = new CapturingAppender();
            created.start();
            coreLogger.addAppender(created);

            this.appender = created;
            this.rootLogger = coreLogger;
            return true;
        } catch (RuntimeException | LinkageError e) {
            pluginLogger.log(Level.WARNING, "Could not attach the log appender; "
                    + "log capture is disabled", e);
            return false;
        }
    }

    /** Detaches the appender. */
    public void detach() {
        if (appender == null) {
            return;
        }
        try {
            if (rootLogger != null) {
                rootLogger.removeAppender(appender);
            }
            appender.stop();
        } catch (RuntimeException | LinkageError e) {
            pluginLogger.log(Level.FINE, "Failed to detach the log appender", e);
        } finally {
            appender = null;
            rootLogger = null;
        }
    }

    public boolean isAttached() {
        return appender != null;
    }

    /** Maps a log4j2 level onto the small set the contract exposes. */
    static LogLevel toContractLevel(int intLevel) {
        if (intLevel <= org.apache.logging.log4j.Level.ERROR.intLevel()) {
            return LogLevel.ERROR;
        }
        if (intLevel <= org.apache.logging.log4j.Level.WARN.intLevel()) {
            return LogLevel.WARN;
        }
        if (intLevel <= org.apache.logging.log4j.Level.INFO.intLevel()) {
            return LogLevel.INFO;
        }
        if (intLevel <= org.apache.logging.log4j.Level.DEBUG.intLevel()) {
            return LogLevel.DEBUG;
        }
        return LogLevel.TRACE;
    }

    /** The appender itself. */
    private final class CapturingAppender extends AbstractAppender {

        @SuppressWarnings("deprecation")
        CapturingAppender() {
            super(APPENDER_NAME, null, null, true);
        }

        @Override
        public void append(LogEvent event) {
            try {
                long timestamp = System.currentTimeMillis();
                LogLevel level = toContractLevel(event.getLevel().intLevel());
                String logger = event.getLoggerName() == null ? "" : event.getLoggerName();
                String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
                String throwableHash = exceptions.record(event.getThrown(), timestamp);

                buffer.append(sequence ->
                        new LogEntry(sequence, timestamp, level, logger, message, throwableHash));
            } catch (RuntimeException | LinkageError e) {

            }
        }
    }
}
