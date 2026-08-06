package moe.vitamin.minecraft.mcp.agent.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import moe.vitamin.minecraft.mcp.contract.ExceptionGroup;

/** Collapses repeated exceptions into one entry each. */
public final class ExceptionRegistry {

    /** Distinct exceptions retained before the least recently seen is evicted. */
    public static final int DEFAULT_MAX_GROUPS = 1_000;

    /** Stack frames folded into the identity hash, counted from the throw site. */
    private static final int HASHED_FRAME_DEPTH = 12;

    /** Cause links followed before giving up. */
    private static final int MAX_CAUSE_DEPTH = 10;

    /** Ceiling on one stored stack trace, so a pathological trace cannot dominate memory. */
    private static final int MAX_STACK_TRACE_CHARS = 16 * 1024;

    private final int maxGroups;
    private final ConcurrentMap<String, Entry> groups = new ConcurrentHashMap<>();

    public ExceptionRegistry() {
        this(DEFAULT_MAX_GROUPS);
    }

    public ExceptionRegistry(int maxGroups) {
        if (maxGroups < 1) {
            throw new IllegalArgumentException("maxGroups must be at least 1 but was: " + maxGroups);
        }
        this.maxGroups = maxGroups;
    }

    /** Records one occurrence. */
    public String record(Throwable throwable, long timestamp) {
        if (throwable == null) {
            return null;
        }

        String hash = hashOf(throwable);
        Entry entry = groups.computeIfAbsent(
                hash, key -> new Entry(key, throwable, timestamp));
        entry.observe(timestamp);

        if (groups.size() > maxGroups) {
            evictLeastRecentlySeen();
        }
        return hash;
    }

    /** The most recently seen exceptions, newest first, without stack traces. */
    public List<ExceptionGroup> recent(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1 but was: " + limit);
        }
        return groups.values().stream()
                .sorted(Comparator.comparingLong((Entry entry) -> entry.lastSeen.get()).reversed())
                .limit(limit)
                .map(entry -> entry.toGroup(false))
                .toList();
    }

    /** One group including its stack trace, or {@code null} if the hash is unknown. */
    public ExceptionGroup byHash(String hash) {
        Entry entry = hash == null ? null : groups.get(hash);
        return entry == null ? null : entry.toGroup(true);
    }

    /** Distinct exceptions currently retained. */
    public int size() {
        return groups.size();
    }

    private void evictLeastRecentlySeen() {
        groups.values().stream()
                .min(Comparator.comparingLong(entry -> entry.lastSeen.get()))
                .ifPresent(oldest -> groups.remove(oldest.hash));
    }

    /** Derives a stable identity from the exception type and where it was thrown. */
    static String hashOf(Throwable throwable) {
        long hash = 0xcbf29ce484222325L;

        Throwable current = throwable;
        for (int link = 0; current != null && link < MAX_CAUSE_DEPTH; link++) {
            hash = fold(hash, current.getClass().getName());

            StackTraceElement[] frames = current.getStackTrace();
            int depth = Math.min(frames.length, HASHED_FRAME_DEPTH);
            for (int i = 0; i < depth; i++) {
                StackTraceElement frame = frames[i];
                hash = fold(hash, frame.getClassName());
                hash = fold(hash, frame.getMethodName());
                hash = fold(hash, Integer.toString(frame.getLineNumber()));
            }

            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return String.format("%016x", hash);
    }

    private static long fold(long hash, String value) {
        long result = hash;
        for (int i = 0; i < value.length(); i++) {
            result ^= value.charAt(i);
            result *= 0x100000001b3L;
        }
        return result ^ 0x2dL;
    }

    private static String stackTraceOf(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        String trace = writer.toString();
        return trace.length() <= MAX_STACK_TRACE_CHARS
                ? trace
                : trace.substring(0, MAX_STACK_TRACE_CHARS) + "\n... [truncated]";
    }

    /** One distinct exception and the running tally of how often it has been seen. */
    private static final class Entry {
        private final String hash;
        private final String type;
        private final String message;
        private final String stackTrace;
        private final long firstSeen;
        private final AtomicLong lastSeen;
        private final AtomicLong count = new AtomicLong(0);

        Entry(String hash, Throwable throwable, long timestamp) {
            this.hash = hash;
            this.type = throwable.getClass().getName();
            this.message = throwable.getMessage();

            this.stackTrace = stackTraceOf(throwable);
            this.firstSeen = timestamp;
            this.lastSeen = new AtomicLong(timestamp);
        }

        void observe(long timestamp) {
            count.incrementAndGet();
            lastSeen.accumulateAndGet(timestamp, Math::max);
        }

        ExceptionGroup toGroup(boolean withStackTrace) {
            return new ExceptionGroup(
                    hash,
                    type,
                    message,
                    count.get(),
                    firstSeen,
                    lastSeen.get(),
                    withStackTrace ? stackTrace : null);
        }
    }

    /** Exposed for tests that need to assert on the raw tally. */
    Map<String, Long> countsByHash() {
        return groups.values().stream()
                .collect(java.util.stream.Collectors.toMap(entry -> entry.hash, entry -> entry.count.get()));
    }
}
