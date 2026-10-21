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
package io.agentscope.builder.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.builder.portal.session.ManagedSessionEventStreams;
import io.agentscope.builder.runtime.managed.ManagedCheckpointObjects;
import io.agentscope.builder.runtime.managed.ManagedJournalStore;
import io.agentscope.builder.runtime.managed.ManagedJournalStore.PublicEvent;
import io.agentscope.builder.web.managed.service.SessionEventNotifier;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.PGConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

/** Uses an isolated database; sixty-second recovery prevents polling from hiding notification bugs. */
@EnabledIfEnvironmentVariable(named = "PORTAL_SSE_TEST_DATABASE_URI", matches = ".+")
class ManagedSessionEventStreamsPostgresTest extends ManagedSessionEventStreamsTest {
    @Override
    protected DataSource database() {
        return new DriverManagerDataSource(
                System.getenv("PORTAL_SSE_TEST_DATABASE_URI"), "managed", "managed-local-test");
    }

    @Test
    void anotherReplicaReceivesLiveCommitsAndReconnectsFromItsOwnCursor() throws Exception {
        var listenerSource = new ListenerSource(source);
        var remote = new SessionEventNotifier(listenerSource, 100);
        ReflectionTestUtils.invokeMethod(remote, "start");
        try {
            listenerReady(listenerSource);
            var other = replica(remote);
            var first = other.stream(scope, 0).next().toFuture();
            initialPage(other);
            append(journal, 1);
            assertEquals(1, first.get(3, TimeUnit.SECONDS).sequence());
            var reconnected = other.stream(scope, 1).next().toFuture();
            initialPage(other);
            append(journal, 2);
            assertEquals(2, reconnected.get(3, TimeUnit.SECONDS).sequence());
        } finally {
            ReflectionTestUtils.invokeMethod(remote, "stop");
        }
    }

    @Test
    void listenerRecoveryImmediatelyReadsCommitsMadeWhileDisconnected() throws Exception {
        var listenerSource = new ListenerSource(source);
        var remote = new SessionEventNotifier(listenerSource, 100);
        var other = replica(remote);
        var observed = new CopyOnWriteArrayList<PublicEvent>();
        var subscription = other.stream(scope, 0).subscribe(observed::add);
        ReflectionTestUtils.invokeMethod(remote, "start");
        try {
            listenerReady(listenerSource);
            initialPage(other);
            listenerSource.blocked.set(true);
            listenerSource.connection.get().close();
            assertTrue(listenerSource.reconnecting.await(3, TimeUnit.SECONDS));
            append(journal, 1);
            assertTrue(observed.isEmpty());
            listenerSource.resume.countDown();
            await(() -> observed.size() == 1);
            assertEquals(1, observed.get(0).sequence());
        } finally {
            listenerSource.resume.countDown();
            subscription.dispose();
            ReflectionTestUtils.invokeMethod(remote, "stop");
        }
    }

    @Test
    void failedStartupConnectionRetriesAndCatchesUpWithoutAnotherEvent() throws Exception {
        var listenerSource = new ListenerSource(source);
        listenerSource.failures.set(1);
        listenerSource.blocked.set(true);
        var remote = new SessionEventNotifier(listenerSource, 100);
        var other = replica(remote);
        var observed = new CopyOnWriteArrayList<PublicEvent>();
        var subscription = other.stream(scope, 0).subscribe(observed::add);
        initialPage(other);
        ReflectionTestUtils.invokeMethod(remote, "start");
        try {
            assertTrue(listenerSource.reconnecting.await(3, TimeUnit.SECONDS));
            append(journal, 1);
            assertTrue(observed.isEmpty());
            listenerSource.resume.countDown();
            await(() -> observed.size() == 1);
            assertEquals(1, observed.get(0).sequence());
        } finally {
            listenerSource.resume.countDown();
            subscription.dispose();
            ReflectionTestUtils.invokeMethod(remote, "stop");
        }
    }

    private ManagedSessionEventStreams replica(SessionEventNotifier notifier) {
        var store =
                new ManagedJournalStore(
                        source, mapper, new ManagedCheckpointObjects(mapper, objects));
        return new ManagedSessionEventStreams(store, notifier, 60000, 50000, 30);
    }

    private void listenerReady(ListenerSource listener) throws Exception {
        await(() -> listener.connection.get() != null);
        int pid = listener.connection.get().unwrap(PGConnection.class).getBackendPID();
        var jdbc = new JdbcTemplate(source);
        await(
                () ->
                        jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM pg_stat_activity WHERE pid=?"
                                                + " AND query='LISTEN builder_session_events'",
                                        Long.class,
                                        pid)
                                == 1);
    }

    private static final class ListenerSource extends DelegatingDataSource {
        private final AtomicReference<Connection> connection = new AtomicReference<>();
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicBoolean blocked = new AtomicBoolean();
        private final CountDownLatch reconnecting = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);

        private ListenerSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (failures.getAndUpdate(value -> Math.max(0, value - 1)) > 0)
                throw new SQLException("Injected initial connection failure");
            if (blocked.get()) {
                reconnecting.countDown();
                try {
                    if (!resume.await(5, TimeUnit.SECONDS))
                        throw new SQLException("Listener reconnect was not released");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Listener interrupted", ex);
                }
            }
            var acquired = super.getConnection();
            connection.set(acquired);
            return acquired;
        }
    }
}
