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
package io.agentcore.managed.server.runtime.managed.projection;

import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore.Event;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore.Progress;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore.Resource;
import java.util.List;
import java.util.Objects;

/** A crash may leave immutable suffix rows, but never a visible incomplete prefix. */
public final class SessionProjectionWriter {
    private final SessionProjectionStore store;

    public SessionProjectionWriter(SessionProjectionStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public boolean apply(
            Progress previous, long commitVersion, List<Event> events, List<Resource> resources) {
        return apply(previous, commitVersion, events, resources, List.of());
    }

    public boolean apply(
            Progress previous,
            long commitVersion,
            List<Event> events,
            List<Resource> resources,
            List<SessionProjectionStore.IndexEntry> indexes) {
        long through =
                events.isEmpty()
                        ? previous.visibleSequence()
                        : events.get(events.size() - 1).sequence();
        return applyBatch(
                previous,
                List.of(new SessionProjectionStore.Commit(commitVersion, through)),
                events,
                resources,
                indexes);
    }

    public boolean applyBatch(
            Progress previous,
            List<SessionProjectionStore.Commit> commits,
            List<Event> events,
            List<Resource> resources,
            List<SessionProjectionStore.IndexEntry> indexes) {
        SessionProjectionStore.validateCommits(previous, commits);
        long through = previous.visibleSequence();
        for (var event : events) {
            if (event.sequence() != ++through)
                throw new ManagedStateException("projection_gap", "Event prefix differs");
        }
        if (through != commits.get(commits.size() - 1).through())
            throw new ManagedStateException("projection_gap", "Manifest prefix differs");
        for (var resource : resources) {
            if (resource.sequence() <= previous.visibleSequence() || resource.sequence() > through)
                throw new IllegalArgumentException("Resource is outside this committed range");
        }
        for (var index : indexes) {
            if (index.sequence() <= previous.visibleSequence() || index.sequence() > through)
                throw new IllegalArgumentException("Index lies outside the committed prefix");
        }
        store.putBatch(previous.build(), events, resources);
        store.putIndexes(previous.build(), indexes);
        // No cross-table transaction is assumed. Stores must acknowledge readable immutable rows.
        return store.advanceBatch(previous, commits);
    }
}
