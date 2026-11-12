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
package io.agentcore.managed.server.agentservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.agentservice.session.ManagedSessionEventStreams;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedCheckpointObjects;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.PublicEvent;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import io.agentcore.managed.server.runtime.managed.RuntimeObjectStore;
import io.agentcore.managed.server.web.managed.service.SessionEventNotifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@Timeout(15)
class ManagedSessionEventStreamsTest {
    // SSE ordering/backpressure tests do not test filesystem durability. Keep hundreds of
    // immutable fixture writes in memory; FileRuntimeObjectStore has its own durability tests.
    final RuntimeObjectStore objects =
            new RuntimeObjectStore() {
                private final Map<String, byte[]> data = new ConcurrentHashMap<>();

                public String backendId() {
                    return "sse-test";
                }

                public Reference put(String scope, String kind, String key, byte[] content) {
                    String path = RuntimeObjectStore.objectKey(scope, kind, key);
                    byte[] previous = data.putIfAbsent(path, content.clone());
                    if (previous != null && !Arrays.equals(previous, content))
                        throw new IllegalStateException("Conflicting immutable fixture");
                    return new Reference(
                            backendId(),
                            scope,
                            kind,
                            key,
                            RuntimeObjectStore.sha256(content),
                            content.length);
                }

                public byte[] read(String scope, String kind, String key) {
                    byte[] value = data.get(RuntimeObjectStore.objectKey(scope, kind, key));
                    if (value == null) throw new IllegalStateException("Missing immutable fixture");
                    return value.clone();
                }

                public boolean exists(String scope, String kind, String key) {
                    return data.containsKey(RuntimeObjectStore.objectKey(scope, kind, key));
                }
            };
    final ObjectMapper mapper = new ObjectMapper();
    final ManagedScope scope =
            new ManagedScope("tenant", "namespace", UUID.randomUUID().toString());
    DataSource source;
    SessionEventNotifier notifications;
    ManagedJournalStore journal;
    ManagedSessionEventStreams streams;

    @BeforeEach
    void setup() {
        source = database();
        ManagedAdmissionStore.initialize(source);
        AgentServiceQueryFixture.admission(source, mapper).createSession(scope, "{}");
        notifications = new SessionEventNotifier(source, 100);
        journal =
                spy(
                        AgentServiceQueryFixture.journal(
                                source,
                                mapper,
                                null,
                                new ManagedCheckpointObjects(mapper, objects),
                                notifications));
        streams = new ManagedSessionEventStreams(journal, notifications, 60000, 50000, 30);
    }

    protected DataSource database() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:sse-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    @Test
    void committedEventsShareLiveReadsAndReleaseTheReaderOnDisconnect() throws Exception {
        var first = new CopyOnWriteArrayList<PublicEvent>();
        var second = new CopyOnWriteArrayList<PublicEvent>();
        var a = streams.stream(scope, 0).subscribe(first::add);
        var b = streams.stream(scope, 0).subscribe(second::add);
        try {
            append(journal, 1);
            await(() -> first.size() == 1 && second.size() == 1);
            clearInvocations(journal);
            append(journal, 2);
            await(() -> first.size() == 2 && second.size() == 2);
            verify(journal).publicEvents(scope, 1, 1);
            assertEquals(first, second);
        } finally {
            a.dispose();
            b.dispose();
        }
        await(() -> ((Map<?, ?>) ReflectionTestUtils.getField(streams, "feeds")).isEmpty());
        var resumed = streams.stream(scope, 1).next().block(Duration.ofSeconds(3));
        assertEquals(2, resumed.sequence());
    }

    @Test
    void rollbackDoesNotWakeReadersAndOneTransactionEmitsOnlyOneSignal() {
        var signals = new ArrayList<String>();
        var subscription =
                notifications.wakeups(scope.messageGroup()).skip(1).subscribe(signals::add);
        try {
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            tx.executeWithoutResult(
                                    ignored -> {
                                        append(journal, 1);
                                        throw new IllegalStateException("rollback");
                                    }));
            assertTrue(signals.isEmpty());
            assertEquals(0, journal.publicHead(scope));
            tx.executeWithoutResult(
                    ignored -> {
                        append(journal, 1);
                        append(journal, 2);
                    });
            project(journal);
            assertEquals(List.of(scope.messageGroup()), signals);
            assertEquals(2, journal.publicHead(scope));
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void joiningObserverImmediatelyCatchesAnEventWhoseNotificationWasLost() throws Exception {
        var first = new CopyOnWriteArrayList<PublicEvent>();
        var held = streams.stream(scope, 0).subscribe(first::add);
        try {
            initialPage(streams);
            // A different notifier emulates a committed event with no local wakeup.
            var silentWriter =
                    AgentServiceQueryFixture.journal(
                            source, mapper, new ManagedCheckpointObjects(mapper, objects));
            append(silentWriter, 1);
            var joined = streams.stream(scope, 0).next().block(Duration.ofSeconds(3));
            assertEquals(1, joined.sequence());
            await(() -> first.size() == 1);
        } finally {
            held.dispose();
        }
    }

    @Test
    void sessionRecoveryTimerRepairsMissedNotifications() throws Exception {
        var recovering = new ManagedSessionEventStreams(journal, notifications, 100, 50000, 30);
        var observed = new CopyOnWriteArrayList<PublicEvent>();
        var held = recovering.stream(scope, 0).subscribe(observed::add);
        try {
            initialPage(recovering);
            append(
                    AgentServiceQueryFixture.journal(
                            source, mapper, new ManagedCheckpointObjects(mapper, objects)),
                    1);
            await(() -> observed.size() == 1);
            assertEquals(1, observed.get(0).sequence());
        } finally {
            held.dispose();
        }
    }

    @Test
    void commitBetweenInitialWatermarkAndReplayIsDeliveredWithoutWaitingForRecovery() {
        var first = new AtomicBoolean(true);
        doAnswer(
                        call -> {
                            long head = (Long) call.callRealMethod();
                            if (first.getAndSet(false)) append(journal, 1);
                            return head;
                        })
                .when(journal)
                .publicHead(scope);
        StepVerifier.create(streams.stream(scope, 0).take(1))
                .expectNextMatches(event -> event.sequence() == 1)
                .expectComplete()
                .verify(Duration.ofSeconds(3));
    }

    @Test
    void stalledObserverTimesOutWhenAnotherEventIsPending() {
        var limited = new ManagedSessionEventStreams(journal, notifications, 60000, 50000, 1);
        StepVerifier.create(limited.stream(scope, 0), 1)
                .then(() -> append(journal, 1))
                .expectNextMatches(event -> event.sequence() == 1)
                .then(() -> append(journal, 2))
                .expectError(TimeoutException.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void replayLimitAppliesToTheWholeWatermarkRatherThanEachSharedPage() {
        var limited = new ManagedSessionEventStreams(journal, notifications, 60000, 256, 30);
        StepVerifier.create(limited.stream(scope, 0))
                .then(
                        () -> {
                            try {
                                initialPage(limited);
                            } catch (InterruptedException ex) {
                                throw new AssertionError(ex);
                            }
                            new TransactionTemplate(new DataSourceTransactionManager(source))
                                    .executeWithoutResult(
                                            ignored -> {
                                                for (int i = 1; i <= 257; i++) append(journal, i);
                                            });
                            project(journal);
                        })
                .expectErrorMatches(
                        error ->
                                error instanceof ManagedStateException state
                                        && state.code().equals("event_replay_limit"))
                // This fixture synchronously fsyncs 257 object packages before the replay
                // assertion.
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void slowObserverDoesNotBlockLiveReadersAndFillsSkippedPagesInOrder() throws Exception {
        var fast = new CopyOnWriteArrayList<PublicEvent>();
        var held = streams.stream(scope, 0).subscribe(fast::add);
        try {
            StepVerifier.create(streams.stream(scope, 0).take(600), 0)
                    .then(
                            () -> {
                                var tx =
                                        new TransactionTemplate(
                                                new DataSourceTransactionManager(source));
                                tx.executeWithoutResult(
                                        ignored -> {
                                            for (int i = 1; i <= 600; i++) append(journal, i);
                                        });
                                project(journal);
                                try {
                                    await(() -> fast.size() == 600);
                                } catch (InterruptedException ex) {
                                    throw new AssertionError(ex);
                                }
                            })
                    .thenRequest(600)
                    .recordWith(ArrayList::new)
                    .expectNextCount(600)
                    .consumeRecordedWith(
                            events -> {
                                long sequence = 0;
                                for (var event : events) assertEquals(++sequence, event.sequence());
                            })
                    .verifyComplete();
        } finally {
            held.dispose();
        }
    }

    void initialPage(ManagedSessionEventStreams reader) throws InterruptedException {
        var feeds = (Map<?, ?>) ReflectionTestUtils.getField(reader, "feeds");
        await(() -> !feeds.isEmpty());
        var feed = feeds.get(scope);
        var pages = (Flux<?>) ReflectionTestUtils.getField(feed, "pages");
        assertTrue(pages.next().block(Duration.ofSeconds(3)) != null);
    }

    void append(ManagedJournalStore writer, int id) {
        writer.resourceEvent(scope, "event-" + id, "session.updated", Map.of("value", id));
        // Source manifests become available only after the execution transaction commits.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) project(writer);
    }

    void project(ManagedJournalStore writer) {
        while (writer.projections().project(scope, 128) > 0) {}
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Condition was not reached within five seconds");
    }
}
