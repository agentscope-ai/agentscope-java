/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.runtime.managed;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Explicit Fake input stream; order follows append arrival, not the durable business sequence. */
public final class InMemoryManagedInputInbox implements ManagedInputInbox {
    private final LongSupplier clock;
    private final Map<String, Stream> streams = new HashMap<>();

    public InMemoryManagedInputInbox(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public synchronized String append(ManagedScope scope, String turnId, Signal signal) {
        String key = key(scope, turnId);
        var stream = stream(key, true);
        var existing = stream.entries.get(signal.inputId());
        if (existing != null) {
            if (!existing.signal().equals(signal))
                throw new IllegalArgumentException("Input identity reused with different signal");
            return existing.cursor();
        }
        String cursor = ++stream.next + "-0";
        stream.entries.put(signal.inputId(), new Entry(cursor, signal));
        stream.cancelled |= signal.type() == Type.CANCEL;
        return cursor;
    }

    @Override
    public synchronized List<Entry> read(
            ManagedScope scope, String turnId, String after, int limit, Duration block) {
        if (limit < 1 || block == null || block.isNegative())
            throw new IllegalArgumentException("Invalid input read limits");
        var stream = stream(key(scope, turnId), false);
        if (stream == null) return List.of();
        long cursor = Long.parseLong(after.split("-", 2)[0]);
        return stream.entries.values().stream()
                .filter(entry -> Long.parseLong(entry.cursor().split("-", 2)[0]) > cursor)
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized boolean cancelled(ManagedScope scope, String turnId) {
        var stream = stream(key(scope, turnId), false);
        return stream != null && stream.cancelled;
    }

    @Override
    public synchronized void expire(ManagedScope scope, String turnId, Duration retention) {
        long duration = ManagedMessageBus.positiveMillis(retention);
        var stream = stream(key(scope, turnId), false);
        if (stream != null) stream.until = Math.addExact(clock.getAsLong(), duration);
    }

    /** Models Redis data loss; callers must refill from the durable input ledger. */
    public synchronized void clear() {
        streams.clear();
    }

    private Stream stream(String key, boolean create) {
        var stream = streams.get(key);
        if (stream != null && stream.until <= clock.getAsLong()) {
            streams.remove(key);
            stream = null;
        }
        if (stream == null && create) {
            stream = new Stream();
            streams.put(key, stream);
        }
        return stream;
    }

    private static String key(ManagedScope scope, String turnId) {
        ManagedScope.requireText(turnId, "turn id");
        return scope.key("inputs:" + turnId);
    }

    private static final class Stream {
        private final Map<String, Entry> entries = new LinkedHashMap<>();
        private long next;
        private long until = Long.MAX_VALUE;
        private boolean cancelled;
    }
}
