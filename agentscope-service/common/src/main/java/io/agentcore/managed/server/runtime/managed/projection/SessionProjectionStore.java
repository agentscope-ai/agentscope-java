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

import io.agentcore.managed.server.runtime.managed.ManagedScope;
import java.util.List;

/** Independent query database. No execution Head, kernel state or live delta belongs here. */
public interface SessionProjectionStore {
    record Build(ManagedScope scope, String id, int eventSegmentSpan, int directorySegmentSpan) {
        public Build {
            if (scope == null
                    || id == null
                    || !id.matches("[A-Za-z0-9_-]{1,128}")
                    || eventSegmentSpan < 1
                    || directorySegmentSpan < 1)
                throw new IllegalArgumentException("Invalid projection build");
        }
    }

    record Progress(Build build, long visibleSequence, long commitVersion, long version) {
        public Progress {
            if (build == null || visibleSequence < 0 || commitVersion < 0 || version < 0)
                throw new IllegalArgumentException("Invalid projection progress");
        }
    }

    record Event(
            long sequence,
            String commitId,
            int ordinal,
            String sourceId,
            String type,
            String payload,
            long occurredAt) {
        public Event {
            if (sequence < 1
                    || ordinal < 0
                    || commitId == null
                    || sourceId == null
                    || type == null
                    || payload == null)
                throw new IllegalArgumentException("Invalid projected event");
            if (List.of("item.delta", "event_start", "event_delta").contains(type))
                throw new IllegalArgumentException("Live delta cannot enter history");
        }
    }

    record Resource(
            String kind,
            String id,
            long sequence,
            long createdSequence,
            boolean deleted,
            String payload) {
        public Resource {
            if (kind == null
                    || id == null
                    || kind.isBlank()
                    || id.isBlank()
                    || sequence < 1
                    || createdSequence < 1
                    || createdSequence > sequence
                    || payload == null)
                throw new IllegalArgumentException("Invalid resource version");
        }
    }

    record IndexEntry(String kind, String position, String resourceId, long sequence) {
        public IndexEntry {
            if (kind == null || position == null || resourceId == null || sequence < 1)
                throw new IllegalArgumentException("Invalid query index entry");
        }
    }

    void putIndexes(Build build, List<IndexEntry> indexes);

    List<IndexEntry> index(
            Progress snapshot, String kind, String after, boolean descending, int limit);

    record DirectoryEntry(long createdSequence, String id) {}

    Progress open(Build build);

    Progress progress(Build build);

    void putEvent(Build build, Event event);

    void putResource(Build build, Resource resource);

    default void putBatch(Build build, List<Event> events, List<Resource> resources) {
        events.forEach(event -> putEvent(build, event));
        resources.forEach(resource -> putResource(build, resource));
    }

    /** A validated contiguous manifest boundary, including commits without public events. */
    record Commit(long version, long through) {}

    static void validateCommits(Progress previous, List<Commit> commits) {
        if (commits.isEmpty() || commits.size() > 128)
            throw new IllegalArgumentException("Commit batch must be 1..128");
        long version = previous.commitVersion(), through = previous.visibleSequence();
        for (var commit : commits) {
            if (commit.version() != ++version || commit.through() < through)
                throw new IllegalArgumentException("Non-contiguous progress");
            through = commit.through();
        }
    }

    /** Publish only after every immutable row is readable. CAS never skips a commit. */
    default boolean advance(Progress expected, long through, long commitVersion) {
        return advanceBatch(expected, List.of(new Commit(commitVersion, through)));
    }

    boolean advanceBatch(Progress expected, List<Commit> commits);

    List<Event> events(Progress snapshot, long after, int limit);

    Resource resource(Progress snapshot, String kind, String id);

    List<DirectoryEntry> directory(
            Progress snapshot, String kind, long afterCreated, String afterId, int limit);
}
