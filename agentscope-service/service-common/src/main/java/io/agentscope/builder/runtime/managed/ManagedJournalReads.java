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

import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLogException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Bounded immutable-prefix reads owned by one Managed execution, never shared across epochs. */
final class ManagedJournalReads {
    private static final int MAX_ENTRIES = 2048;
    private static final long MAX_WEIGHT = 4 * 1024 * 1024;

    private record Key(long sequence, SessionEvent source) {}

    private final ManagedScope scope;
    private final ManagedJournalStore journal;
    private final LinkedHashMap<Key, SessionEvent> events = new LinkedHashMap<>(16, 0.75f, true);
    private long weight;

    ManagedJournalReads(ManagedScope scope, ManagedJournalStore journal) {
        this.scope = scope;
        this.journal = journal;
    }

    List<SessionEvent> readAfter(long after, int limit, long through) {
        if (after < 0 || limit < 1 || limit > 1000)
            throw new IllegalArgumentException("Invalid local log page");
        if (after >= through) return List.of();
        int count = (int) Math.min(limit, through - after);
        var cached = new ArrayList<SessionEvent>(count);
        for (long sequence = after + 1; sequence <= after + count; sequence++) {
            var event = events.get(new Key(sequence, null));
            if (event == null) break;
            cached.add(event);
        }
        if (cached.size() == count) return List.copyOf(cached);
        var loaded = journal.nativeManifests(scope, after, count);
        long expected = after;
        for (var event : loaded) {
            if (event.seq() != ++expected)
                throw new SessionLogException("Missing committed prefix");
            put(new Key(event.seq(), null), event);
        }
        if (loaded.size() != count) throw new SessionLogException("Missing committed prefix");
        return loaded;
    }

    SessionEvent materialize(SessionEvent event) {
        var key = new Key(event.seq(), event);
        var cached = events.get(key);
        if (cached != null) return cached;
        var resolved = journal.objects().hydrate(scope, event);
        if (resolved != event) put(key, resolved);
        return resolved;
    }

    void committed(List<SessionEvent> committed) {
        for (var event : committed) put(new Key(event.seq(), null), event);
    }

    private void put(Key key, SessionEvent event) {
        long size = weight(key, event);
        if (size > MAX_WEIGHT) return;
        var previous = events.put(key, event);
        if (previous != null) weight -= weight(key, previous);
        weight += size;
        while (events.size() > MAX_ENTRIES || weight > MAX_WEIGHT) {
            var oldest = events.entrySet().iterator();
            var entry = oldest.next();
            weight -= weight(entry.getKey(), entry.getValue());
            oldest.remove();
        }
    }

    private static long weight(Key key, SessionEvent event) {
        return weight(event) + (key.source() == null ? 0 : weight(key.source()));
    }

    private static long weight(SessionEvent event) {
        return 256L
                + 3L
                        * (event.payloadJson().length()
                                + event.eventId().length()
                                + (event.executionRunId() == null
                                        ? 0
                                        : event.executionRunId().length())
                                + (event.turnId() == null ? 0 : event.turnId().length()));
    }
}
