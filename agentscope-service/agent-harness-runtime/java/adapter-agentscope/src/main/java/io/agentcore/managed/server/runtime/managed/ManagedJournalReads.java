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
package io.agentcore.managed.server.runtime.managed;

import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        List<SessionEvent> loaded;
        try {
            loaded = journal.recoveryManifests(scope, after, count);
        } catch (ManagedStateException invalid) {
            if ("native_sequence_conflict".equals(invalid.code()))
                throw new SessionLogException("Missing committed prefix");
            throw invalid;
        }
        long expected = after;
        for (var event : loaded) {
            if (event.seq() != ++expected)
                throw new SessionLogException("Missing committed prefix");
            put(new Key(event.seq(), null), event);
        }
        if (loaded.size() != count) throw new SessionLogException("Missing committed prefix");
        return loaded;
    }

    private record Page(long after, int limit, long through) {}

    private final LinkedHashMap<Page, List<SessionEvent>> recoveryPages =
            new LinkedHashMap<>(16, .75f, true);
    private long recoveryWeight;

    List<SessionEvent> readExecution(
            long after, int limit, SessionRecoveryHead view, String turn, long first) {
        var key = new Page(after, limit, view.sequence());
        var cached = recoveryPages.get(key);
        if (cached != null) return cached;
        List<SessionEvent> loaded = null;
        if (first > 0 && Math.max(after + 1, first) <= view.sequence()) {
            var warm = new ArrayList<SessionEvent>();
            if (view.checkpoint() != null
                    && view.checkpointSequence() > after
                    && view.checkpointSequence() < first)
                warm.add(journal.recoveryBaseline(view.checkpoint()));
            boolean complete = true;
            for (long sequence = Math.max(after + 1, first);
                    sequence <= view.sequence();
                    sequence++) {
                var event = events.get(new Key(sequence, null));
                if (event == null) {
                    long position = sequence;
                    boolean oldSnapshot =
                            position < view.checkpointSequence()
                                    && recoveryPages.values().stream()
                                            .flatMap(List::stream)
                                            .anyMatch(
                                                    previous ->
                                                            previous.seq() == position
                                                                    && previous.eventId()
                                                                            .startsWith(
                                                                                    "kernel_snapshot_"));
                    if (oldSnapshot) continue;
                    complete = false;
                    break;
                }
                if (sequence == view.checkpointSequence())
                    warm.add(journal.recoveryBaseline(view.checkpoint()));
                else if (sequence > view.checkpointSequence()
                        || !List.of("state/checkpoint", "migration/baseline", "state/restored")
                                .contains(event.type())) warm.add(event);
            }
            if (complete) loaded = warm.stream().limit(limit).toList();
        }
        if (loaded == null) {
            try {
                loaded = journal.recoveryBootstrap(scope, turn, first, after, limit, view);
            } catch (ManagedStateException invalid) {
                if ("recovery_sequence_conflict".equals(invalid.code()))
                    throw new SessionLogException(
                            "Missing committed execution fact prefix", invalid);
                throw invalid;
            }
        }
        long size = loaded.stream().mapToLong(ManagedJournalReads::weight).sum();
        if (size <= MAX_WEIGHT) {
            recoveryPages.put(key, loaded);
            recoveryWeight += size;
            while (recoveryPages.size() > 16 || recoveryWeight > MAX_WEIGHT) {
                var oldest = recoveryPages.entrySet().iterator();
                var entry = oldest.next();
                recoveryWeight -=
                        entry.getValue().stream().mapToLong(ManagedJournalReads::weight).sum();
                oldest.remove();
            }
        }
        return loaded;
    }

    SessionEvent materialize(SessionEvent event) {
        var key = new Key(event.seq(), event);
        var cached = events.get(key);
        if (cached != null) return cached;
        SessionEvent resolved;
        if (event.eventId().startsWith("kernel_snapshot_")) {
            if (event.data().size() != 1
                    || event.data().get(ManagedCheckpointObjects.REFERENCE) == null)
                throw new ManagedStateException(
                        "checkpoint_manifest_invalid", "Kernel snapshot descriptor differs");
            var reference =
                    journal.objects()
                            .decodeReference(
                                    scope,
                                    JsonUtils.getJsonCodec()
                                            .toJson(
                                                    event.data()
                                                            .get(
                                                                    ManagedCheckpointObjects
                                                                            .REFERENCE)));
            var identity = reference.identity();
            if (!event.eventId().equals("kernel_snapshot_" + identity.checkpointId())
                    || event.seq() != identity.nativeSequence()
                    || !Objects.equals(event.turnId(), identity.turnId())
                    || !Objects.equals(event.executionRunId(), identity.runId()))
                throw new ManagedStateException(
                        "checkpoint_manifest_invalid", "Kernel snapshot identity differs");
            var image = journal.objects().read(reference);
            resolved =
                    new SessionEvent(
                            event.schemaVersion(),
                            event.eventId(),
                            event.seq(),
                            event.occurredAt(),
                            event.type(),
                            event.executionRunId(),
                            event.turnId(),
                            true,
                            JsonUtils.getJsonCodec()
                                    .toJson(Map.of("stateJson", image.stateJson())));
        } else resolved = journal.objects().hydrate(scope, event);
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
