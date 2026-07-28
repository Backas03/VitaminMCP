package moe.vitamin.minecraft.mcp.contract;

import java.util.Objects;

/**
 * A position in one of the agent's append-only streams.
 *
 * <p>Every captured record gets a monotonically increasing sequence number, and a cursor is
 * simply the sequence to resume after. Paging therefore never re-reads or skips a record even
 * while capture continues, which is the reason cursors exist from the first version rather
 * than being retrofitted once responses start overflowing.
 *
 * <p>The stream name is part of the encoded form so that feeding an events cursor to a logs
 * query fails loudly instead of silently paging through the wrong data.
 */
public record Cursor(String stream, long sequence) {

    /** Stream name for captured Bukkit events. */
    public static final String EVENTS = "events";

    /** Stream name for captured log entries. */
    public static final String LOGS = "logs";

    public Cursor {
        Objects.requireNonNull(stream, "stream");
        if (stream.isEmpty()) {
            throw new IllegalArgumentException("stream must not be empty");
        }
        if (stream.indexOf(':') >= 0) {
            throw new IllegalArgumentException("stream must not contain ':' but was: " + stream);
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative but was: " + sequence);
        }
    }

    /** The cursor that starts at the very beginning of {@code stream}. */
    public static Cursor start(String stream) {
        return new Cursor(stream, 0L);
    }

    /**
     * Encodes the cursor into the opaque token clients pass back.
     *
     * <p>The format is intentionally readable rather than base64: these tokens show up in logs
     * and in LLM transcripts, and being able to tell at a glance which record a client resumed
     * from is worth more here than hiding the representation.
     */
    public String encode() {
        return stream + ':' + sequence;
    }

    /**
     * Parses a token produced by {@link #encode()}.
     *
     * @throws IllegalArgumentException if the token is malformed, or belongs to another stream
     */
    public static Cursor parse(String token, String expectedStream) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(expectedStream, "expectedStream");

        int separator = token.indexOf(':');
        if (separator < 0) {
            throw new IllegalArgumentException("Malformed cursor: " + token);
        }

        String stream = token.substring(0, separator);
        if (!expectedStream.equals(stream)) {
            throw new IllegalArgumentException(
                    "Cursor belongs to stream '" + stream + "' but was used to query '" + expectedStream + "'");
        }

        long sequence;
        try {
            sequence = Long.parseLong(token.substring(separator + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed cursor: " + token, e);
        }
        return new Cursor(stream, sequence);
    }
}
