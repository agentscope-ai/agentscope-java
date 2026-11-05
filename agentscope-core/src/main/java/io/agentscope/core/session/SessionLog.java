/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.session;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.BiFunction;

/** Blocking persistence SPI; runtime integration schedules it off reactive event-loop threads. */
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
        return scanRange(after, through, this::readAfter);
    }

    /**
     * Reads event envelopes and ordinary payloads without loading external state bodies. Backends
     * that externalize state may return references for those bodies; use {@link #materialize} when
     * a consumer needs the original payload. Existing backends return their ordinary events.
     */
    default List<SessionEvent> readMetadataAfter(long after, int limit) {
        return readAfter(after, limit);
    }

    /** Scans the same stable prefix as {@link #scan}, retaining events whose state is external. */
    default Iterable<SessionEvent> scanMetadata(long after, long through) {
        return scanRange(after, through, this::readMetadataAfter);
    }

    /** Resolves and validates an external state body without changing the event's identity. */
    default SessionEvent materialize(SessionEvent event) {
        return event;
    }

    private static Iterable<SessionEvent> scanRange(
            long after, long through, BiFunction<Long, Integer, List<SessionEvent>> read) {
        return () ->
                new Iterator<SessionEvent>() {
                    long cursor = after;
                    Iterator<SessionEvent> batch = List.<SessionEvent>of().iterator();

                    public boolean hasNext() {
                        if (cursor >= through) return false;
                        if (!batch.hasNext())
                            batch =
                                    read.apply(cursor, (int) Math.min(256, through - cursor))
                                            .iterator();
                        if (!batch.hasNext())
                            throw new SessionLogException("Missing committed prefix");
                        return true;
                    }

                    public SessionEvent next() {
                        if (!hasNext()) throw new NoSuchElementException();
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
