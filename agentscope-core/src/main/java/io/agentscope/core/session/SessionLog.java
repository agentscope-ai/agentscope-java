package io.agentscope.core.session;

import java.time.Duration;
import java.util.List;

/** Blocking persistence SPI; runtime integration schedules it on boundedElastic. */
public interface SessionLog {
    /** Durable input journal, independent of the execution writer and its event sequence. */
    default SessionLog inbox() {
        throw new UnsupportedOperationException(
                "This session backend does not support a durable inbox");
    }

    record Head(long seq, long epoch, String owner, long leaseUntil, String commitId) {}

    record Writer(String owner, long epoch) {}

    default long exportCursor(String sink) {
        return 0;
    }

    default void advanceExportCursor(String sink, long expected, long next) {
        throw new UnsupportedOperationException("Export cursor persistence not implemented");
    }

    /** Stable-prefix scan. Implementations can avoid repeatedly traversing a commit chain. */
    default Iterable<SessionEvent> scan(long after, long through) {
        return () ->
                new java.util.Iterator<SessionEvent>() {
                    long cursor = after;
                    java.util.Iterator<SessionEvent> batch = List.<SessionEvent>of().iterator();

                    public boolean hasNext() {
                        if (cursor >= through) return false;
                        if (!batch.hasNext())
                            batch =
                                    readAfter(cursor, (int) Math.min(256, through - cursor))
                                            .iterator();
                        if (!batch.hasNext())
                            throw new SessionLogException("Missing committed prefix");
                        return true;
                    }

                    public SessionEvent next() {
                        if (!hasNext()) throw new java.util.NoSuchElementException();
                        var event = batch.next();
                        cursor = event.seq();
                        return event;
                    }
                };
    }

    Head head();

    Writer acquire(String owner, Duration lease);

    /**
     * Permanently fences this writer identity, including an acquisition that has not started yet.
     * Other writer identities may continue this session. Returns a stable prefix for the sealed
     * writer; implementations must serialize sealing against acquisition and commit.
     */
    default Head sealWriter(String owner) {
        throw new UnsupportedOperationException("This session backend cannot seal a writer");
    }

    void renew(Writer writer, Duration lease);

    void release(Writer writer);

    Head commit(Writer writer, String batchId, long expectedSeq, List<SessionEvent> events);

    List<SessionEvent> readAfter(long after, int limit);
}
