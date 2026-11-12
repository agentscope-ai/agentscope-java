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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.projection.PostgresSessionProjectionStore;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore.Build;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore.Event;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore.Resource;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionWriter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SessionProjectionStoreTest {
    DataSource source;
    SessionProjectionStore store;
    final Build build =
            new Build(
                    new ManagedScope("tenant", "namespace", "session-" + UUID.randomUUID()),
                    "build-1",
                    2,
                    2);

    protected DataSource database() throws Exception {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:query-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    @BeforeEach
    void setup() throws Exception {
        source = database();
        PostgresSessionProjectionStore.initialize(source);
        store = new PostgresSessionProjectionStore(source, new ObjectMapper());
    }

    Event event(long seq) {
        return new Event(seq, "commit-" + seq, 0, "event-" + seq, "item.completed", "{}", 1);
    }

    Resource resource(long seq, boolean deleted) {
        return new Resource("items", "message-1", seq, 1, deleted, "{\"value\":" + seq + "}");
    }

    @Test
    void anUnpublishedSnapshotCannotExposeAPartiallyWrittenSuffix() {
        var before = store.open(build);
        store.putEvent(build, event(1));
        store.putResource(build, resource(1, false));
        var forged = new SessionProjectionStore.Progress(build, 1, 1, 1);
        assertThrows(ManagedStateException.class, () -> store.events(forged, 0, 10));
        assertThrows(
                ManagedStateException.class, () -> store.resource(forged, "items", "message-1"));
        assertThrows(
                ManagedStateException.class, () -> store.directory(forged, "items", 0, "", 10));
        assertTrue(store.events(before, 0, 10).isEmpty());
    }

    @Test
    void queryStoreNeedsNoExecutionTablesAndRejectsLayoutChanges() {
        store.open(build);
        assertEquals(
                0,
                new JdbcTemplate(source)
                        .queryForObject(
                                "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                        + " lower(table_schema)='ma'",
                                Integer.class));
        assertThrows(
                ManagedStateException.class,
                () -> store.open(new Build(build.scope(), build.id(), 3, 2)));
    }

    @Test
    void interruptedWritesRemainInvisibleAndReplayPublishesSamePrefix() {
        var before = store.open(build);
        var broken = spy(store);
        doAnswer(
                        call -> {
                            store.putEvent(build, event(1));
                            throw new IllegalStateException(
                                    "query store interrupted after partial write");
                        })
                .when(broken)
                .putBatch(build, List.of(event(1)), List.of(resource(1, false)));
        var failed = new SessionProjectionWriter(broken);
        assertThrows(
                IllegalStateException.class,
                () -> failed.apply(before, 1, List.of(event(1)), List.of(resource(1, false))));
        assertEquals(before, store.progress(build));
        assertTrue(store.events(before, 0, 10).isEmpty());
        assertNull(store.resource(before, "items", "message-1"));
        assertTrue(
                new SessionProjectionWriter(store)
                        .apply(before, 1, List.of(event(1)), List.of(resource(1, false))));
        var after = store.progress(build);
        assertEquals(List.of(event(1)), store.events(after, 0, 10));
        assertEquals(resource(1, false), store.resource(after, "items", "message-1"));
    }

    @Test
    void pinnedSnapshotsKeepOldVersionsAcrossUpdatesDeletionAndRebuild() {
        var writer = new SessionProjectionWriter(store);
        writer.apply(store.open(build), 1, List.of(event(1)), List.of(resource(1, false)));
        var pinned = store.progress(build);
        writer.apply(pinned, 2, List.of(event(2)), List.of(resource(2, false)));
        writer.apply(store.progress(build), 3, List.of(event(3)), List.of(resource(3, true)));
        var latest = store.progress(build);
        assertEquals(resource(1, false), store.resource(pinned, "items", "message-1"));
        assertTrue(store.resource(latest, "items", "message-1").deleted());
        assertEquals(1, store.events(pinned, 0, 10).size());
        assertEquals(3, store.events(latest, 0, 10).size());
        assertEquals(
                List.of(new SessionProjectionStore.DirectoryEntry(1, "message-1")),
                store.directory(latest, "items", 0, "", 10));
        var rebuild = store.open(new Build(build.scope(), "build-2", 5, 5));
        assertTrue(store.events(rebuild, 0, 10).isEmpty());
        assertEquals(resource(1, false), store.resource(pinned, "items", "message-1"));
    }

    @Test
    void concurrentConsumersPublishOnlyOnceAndEmptyCommitsAdvanceVersion() throws Exception {
        var before = store.open(build);
        var writer = new SessionProjectionWriter(store);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> writer.apply(before, 1, List.of(event(1)), List.of()));
            var second = pool.submit(() -> writer.apply(before, 1, List.of(event(1)), List.of()));
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertTrue(writer.apply(store.progress(build), 2, List.of(), List.of()));
        assertEquals(1, store.progress(build).visibleSequence());
        assertEquals(2, store.progress(build).commitVersion());
        assertEquals(1, store.events(store.progress(build), 0, 10).size());
    }

    @Test
    void contentConflictsAndGapsCannotAdvanceVisibility() {
        var before = store.open(build);
        var writer = new SessionProjectionWriter(store);
        assertThrows(
                ManagedStateException.class,
                () -> writer.apply(before, 1, List.of(event(2)), List.of()));
        store.putEvent(build, event(1));
        var changed = new Event(1, "commit-1", 0, "event-1", "item.completed", "{\"other\":1}", 1);
        assertThrows(
                ManagedStateException.class,
                () -> writer.apply(before, 1, List.of(changed), List.of()));
        assertEquals(before, store.progress(build));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Event(1, "c", 0, "e", "event_delta", "{}", 1));
    }

    @Test
    void contiguousBatchPublishesOnePrefixAndKeepsEmptyCommitAndVersionHistory() {
        var before = store.open(build);
        var commits =
                List.of(
                        new SessionProjectionStore.Commit(1, 1),
                        new SessionProjectionStore.Commit(2, 1),
                        new SessionProjectionStore.Commit(3, 2));
        var writer = new SessionProjectionWriter(store);
        assertTrue(
                writer.applyBatch(
                        before,
                        commits,
                        List.of(event(1), event(2)),
                        List.of(resource(1, false), resource(2, false)),
                        List.of()));
        var after = store.progress(build);
        assertEquals(3, after.commitVersion());
        assertEquals(1, after.version());
        assertEquals(List.of(event(1), event(2)), store.events(after, 0, 10));
        assertEquals(resource(2, false), store.resource(after, "items", "message-1"));
        assertTrue(store.events(before, 0, 10).isEmpty());
        assertEquals(
                false,
                writer.applyBatch(
                        before,
                        commits,
                        List.of(event(1), event(2)),
                        List.of(resource(1, false), resource(2, false)),
                        List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        writer.applyBatch(
                                after,
                                List.of(new SessionProjectionStore.Commit(5, 2)),
                                List.of(),
                                List.of(),
                                List.of()));
        assertEquals(after, store.progress(build));
    }

    @Test
    void interruptedBatchCannotPublishPartialPrefixAndReplayMayUseSmallerBatches() {
        var before = store.open(build);
        var broken = spy(store);
        var events = List.of(event(1), event(2));
        var resources = List.of(resource(1, false), resource(2, false));
        doAnswer(
                        call -> {
                            store.putEvent(build, event(1));
                            store.putResource(build, resource(1, false));
                            throw new ManagedTransportException("Interrupted immutable batch");
                        })
                .when(broken)
                .putBatch(build, events, resources);
        var commits =
                List.of(
                        new SessionProjectionStore.Commit(1, 1),
                        new SessionProjectionStore.Commit(2, 2));
        assertThrows(
                ManagedTransportException.class,
                () ->
                        new SessionProjectionWriter(broken)
                                .applyBatch(before, commits, events, resources, List.of()));
        assertEquals(before, store.progress(build));
        assertNull(store.resource(before, "items", "message-1"));
        var writer = new SessionProjectionWriter(store);
        assertTrue(writer.apply(before, 1, List.of(event(1)), List.of(resource(1, false))));
        var pinned = store.progress(build);
        assertTrue(writer.apply(pinned, 2, List.of(event(2)), List.of(resource(2, false))));
        assertEquals(resource(1, false), store.resource(pinned, "items", "message-1"));
    }

    @Test
    void reusedResourceCreationIdentityIsRejectedBeforeWatermark() {
        store.open(build);
        store.putResource(build, resource(1, false));
        assertThrows(
                ManagedStateException.class,
                () ->
                        store.putResource(
                                build, new Resource("items", "message-1", 2, 2, false, "{}")));
        assertEquals(0, store.progress(build).visibleSequence());
    }
}
