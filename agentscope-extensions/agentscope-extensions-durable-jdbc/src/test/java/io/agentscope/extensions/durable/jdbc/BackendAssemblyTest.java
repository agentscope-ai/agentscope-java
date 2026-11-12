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

package io.agentscope.extensions.durable.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.SessionSpec;
import io.agentscope.durable.DurableTypes.ThreadBinding;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.Wakeups;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

/** Complete adapters use the same application API, locally and on a real PostgreSQL server. */
class BackendAssemblyTest {
    @TempDir Path directory;
    final SessionKey session = new SessionKey("application", "session");
    final String postgresUrl = System.getProperty("durable.test.jdbcUrl");
    String schema;
    PGSimpleDataSource dataSource;
    SessionBackend backend;

    @BeforeEach
    void setup() throws Exception {
        if (postgresUrl != null) {
            schema = "durable_assembly_" + UUID.randomUUID().toString().replace("-", "");
            try (var connection = DriverManager.getConnection(postgresUrl);
                    var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
            }
            dataSource = new PGSimpleDataSource();
            dataSource.setUrl(postgresUrl);
            dataSource.setCurrentSchema(schema);
            // Real connections with a pool-like manual-commit default exercise DDL persistence.
            var initializer = mock(DataSource.class);
            when(initializer.getConnection())
                    .thenAnswer(
                            invocation -> {
                                var connection = dataSource.getConnection();
                                connection.setAutoCommit(false);
                                return connection;
                            });
            PostgresBackend.initializeSchema(initializer);
        }
        backend = open();
        backend.create(
                new SessionSpec(
                        session, new ThreadBinding("fixture", "v1", "1"), DurableJson.value(null)));
    }

    SessionBackend open() {
        return dataSource == null
                ? LocalBackend.open(directory.resolve("nested/data"))
                : PostgresBackend.builder().dataSource(dataSource).build();
    }

    DurableRuntime runtime() {
        return DurableRuntime.builder()
                .backend(backend)
                .pollInterval(Duration.ofMillis(20))
                .build();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (backend != null) backend.close();
        if (schema != null)
            try (var connection = DriverManager.getConnection(postgresUrl);
                    var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
    }

    @Test
    void completeBackendReopensOriginalAdmissionAndClosesOnlyItsOwnStore() throws Exception {
        var notifications = new AtomicInteger();
        var other = open();
        try (other;
                var subscription =
                        backend.wakeups().subscribe(key -> notifications.incrementAndGet())) {
            var receipt =
                    backend.submit(
                            new ThreadKey(session, "main"), "request", DurableJson.value("hello"));
            assertEquals(1, notifications.get());
            assertTrue(backend.capabilities().durable());
            assertEquals(dataSource != null, backend.capabilities().sharedAcrossHosts());
            backend.close();
            backend.close();
            assertThrows(IllegalStateException.class, () -> backend.snapshot(session));
            assertEquals(receipt, other.admission(session, "request").orElseThrow());
            backend = open();
            assertEquals(
                    receipt,
                    backend.submit(
                            new ThreadKey(session, "main"), "request", DurableJson.value("hello")));
            if (dataSource == null)
                assertTrue(Files.isRegularFile(directory.resolve("nested/data/durable.db")));
        }
    }

    @Test
    void managedObserverBootstrapsRetriesCallbackAndResumesFromAcknowledgedCursor()
            throws Exception {
        backend.submit(new ThreadKey(session, "main"), "before", DurableJson.value("input"));
        var snapshotReady = new CountDownLatch(1);
        var delivered = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var errors = new AtomicInteger();
        try (var runtime =
                DurableRuntime.builder()
                        .backend(backend)
                        .pollInterval(Duration.ofMillis(20))
                        .onError(
                                (key, error) -> {
                                    errors.incrementAndGet();
                                    throw new IllegalStateException("Reporter failed");
                                })
                        .build()) {
            String cursor;
            try (var observer =
                    runtime.getSession(session)
                            .observe(
                                    snapshot -> {
                                        assertEquals(1, snapshot.turns().size());
                                        snapshotReady.countDown();
                                    },
                                    event -> {
                                        if (calls.incrementAndGet() == 1)
                                            throw new DurableException(
                                                    DurableException.Code.CURSOR_EXPIRED,
                                                    "Business callback failure");
                                        delivered.countDown();
                                    })) {
                assertTrue(snapshotReady.await(5, TimeUnit.SECONDS));
                runtime.getSession(session).submit("after", "input");
                assertTrue(delivered.await(5, TimeUnit.SECONDS));
                // cursor is set after the callback returns; wait for that acknowledgement.
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                String expected = runtime.getSession(session).snapshot().cursor();
                while (!expected.equals(observer.cursor()) && System.nanoTime() < until)
                    Thread.sleep(1);
                cursor = observer.cursor();
                assertEquals(expected, cursor);
                assertEquals(3, calls.get()); // Queued + input message, with one callback retry.
                assertEquals(1, errors.get());
            }
            var resumed = new CountDownLatch(1);
            var third = runtime.getSession(session).submit("third", "input");
            try (var observer =
                    runtime.getSession(session)
                            .observe(
                                    cursor,
                                    snapshot -> {
                                        throw new AssertionError("Unexpected snapshot");
                                    },
                                    event -> {
                                        assertEquals(third.turnId(), event.turnId());
                                        resumed.countDown();
                                    })) {
                assertTrue(resumed.await(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void missedHintsAndFailedSnapshotCallbackRecoverWithoutCallerPolling() throws Exception {
        backend = backend.withWakeups(Wakeups.none());
        backend.submit(new ThreadKey(session, "main"), "one", DurableJson.value("input"));
        String old = backend.snapshot(session).cursor();
        backend.submit(new ThreadKey(session, "main"), "two", DurableJson.value("input"));
        backend.pruneEvents(session, backend.snapshot(session).cursor());
        var resets = new AtomicInteger();
        var reset = new CountDownLatch(1);
        var delivered = new CountDownLatch(1);
        try (var runtime = runtime();
                var observer =
                        runtime.getSession(session)
                                .observe(
                                        old,
                                        snapshot -> {
                                            if (resets.incrementAndGet() == 1)
                                                throw new IllegalStateException(
                                                        "Projection unavailable");
                                            assertEquals(2, snapshot.turns().size());
                                            reset.countDown();
                                        },
                                        event -> delivered.countDown())) {
            assertTrue(reset.await(5, TimeUnit.SECONDS));
            runtime.getSession(session).submit("three", "input");
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals(2, resets.get());
        }
    }

    @Test
    void closingObserversDetachesIndependentlyAndRuntimeClosesRemainingSubscriptions()
            throws Exception {
        var local = Wakeups.local();
        var active = new AtomicInteger();
        backend =
                backend.withWakeups(
                        new Wakeups() {
                            public void publish(SessionKey key) {
                                local.publish(key);
                            }

                            public AutoCloseable subscribe(Consumer<SessionKey> listener) {
                                active.incrementAndGet();
                                var registered = local.subscribe(listener);
                                return () -> {
                                    registered.close();
                                    active.decrementAndGet();
                                };
                            }
                        });
        var snapshots = new CountDownLatch(2);
        var delivered = new CountDownLatch(1);
        var firstCalls = new AtomicInteger();
        try (var runtime = runtime();
                var first =
                        runtime.getSession(session)
                                .observe(
                                        s -> snapshots.countDown(),
                                        e -> firstCalls.incrementAndGet());
                var second =
                        runtime.getSession(session)
                                .observe(s -> snapshots.countDown(), e -> delivered.countDown())) {
            assertTrue(snapshots.await(5, TimeUnit.SECONDS));
            first.close();
            first.close();
            assertEquals(1, active.get());
            var turn = runtime.getSession(session).submit("request", "input");
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals(0, firstCalls.get());
            runtime.close();
            assertTrue(second.isClosed());
            assertEquals(0, active.get());
            assertFalse(
                    backend.turn(new ThreadKey(session, "main"), turn.turnId())
                            .cancellationRequested());
            assertThrows(
                    IllegalStateException.class,
                    () -> runtime.getSession(session).observe(s -> {}, e -> {}));
            assertEquals(1, backend.snapshot(session).turns().size());
        }
    }

    @Test
    void slowCallbackKeepsSerialOrderWhileHintsAreCoalesced() throws Exception {
        var initialized = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(6);
        var seen = new CopyOnWriteArrayList<String>();
        try (var runtime = runtime();
                var observer =
                        runtime.getSession(session)
                                .observe(
                                        snapshot -> initialized.countDown(),
                                        event -> {
                                            seen.add(event.turnId());
                                            if (seen.size() == 1) {
                                                entered.countDown();
                                                try {
                                                    assertTrue(release.await(5, TimeUnit.SECONDS));
                                                } catch (InterruptedException error) {
                                                    throw new IllegalStateException(error);
                                                }
                                            }
                                            completed.countDown();
                                        })) {
            assertTrue(initialized.await(5, TimeUnit.SECONDS));
            var one = runtime.getSession(session).submit("one", "input");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var two = runtime.getSession(session).submit("two", "input");
            var three = runtime.getSession(session).submit("three", "input");
            for (int i = 0; i < 100; i++) backend.wakeups().publish(session);
            assertEquals(List.of(one.turnId()), seen);
            release.countDown();
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(
                    List.of(
                            one.turnId(),
                            one.turnId(),
                            two.turnId(),
                            two.turnId(),
                            three.turnId(),
                            three.turnId()),
                    seen);
        } finally {
            release.countDown();
        }
    }
}
