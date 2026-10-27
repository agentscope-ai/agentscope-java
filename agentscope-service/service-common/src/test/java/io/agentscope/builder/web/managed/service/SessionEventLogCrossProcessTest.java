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
package io.agentscope.builder.web.managed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.BuilderCommonTestApp;
import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.persistence.jpa.SessionEventEntityRepository;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.test.StepVerifier;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = BuilderCommonTestApp.class)
@Import(SessionEventLogCrossProcessTest.Config.class)
@TestPropertySource(
        properties = {
            "spring.datasource.url=${agentscope.test.jdbc-url:jdbc:h2:mem:sessionEventLog;DB_CLOSE_DELAY=-1;MODE=MYSQL}",
            "spring.datasource.username=${agentscope.test.jdbc-username:sa}",
            "spring.datasource.password=${agentscope.test.jdbc-password:}",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "builder.session-event.recovery-interval-ms=60000"
        })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionEventLogCrossProcessTest {

    @Autowired SessionEventLog eventLog;
    @Autowired SessionEventEntityRepository repository;
    @Autowired DeletedSessionRegistry deletedSessions;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ManagedJsonHelper jsonHelper;
    @Autowired SessionEventNotifier notifier;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void sameSessionWaitsBeforeReadingSequenceWhileOtherSessionsProceed() throws Exception {
        String sessionId = "ses_locked";
        eventLog.append(sessionId, "run.started", Map.of());
        SessionEventLog otherNode = node(repository);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SessionEventDto> sameSession;
            var held = transactionManager.getTransaction(new DefaultTransactionDefinition());
            try {
                new JdbcTemplate(dataSource)
                        .queryForList(
                                "select next_seq from builder_session_event_sequence where"
                                        + " session_id = ? for update",
                                sessionId);
                CountDownLatch started = new CountDownLatch(1);
                sameSession =
                        pool.submit(
                                () -> {
                                    started.countDown();
                                    return otherNode.append(
                                            sessionId, "item.delta", Map.of("text", "next"));
                                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(
                                pool.submit(
                                                () ->
                                                        otherNode.append(
                                                                "ses_independent",
                                                                "run.started",
                                                                Map.of()))
                                        .get(10, TimeUnit.SECONDS)
                                        .seq())
                        .isEqualTo(1L);
                assertThatThrownBy(() -> sameSession.get(300, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                transactionManager.rollback(held);
            }
            assertThat(sameSession.get(10, TimeUnit.SECONDS).seq()).isEqualTo(2L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sustainedConcurrentAppendsAcrossNodesDoNotRetrySequences() throws Exception {
        String sessionId = "ses_streaming";
        eventLog.append(sessionId, "run.started", Map.of());
        List<SessionEventLog> nodes = List.of(node(repository), node(repository));
        int writers = 8;
        int eventsPerWriter = 40;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> pending = new ArrayList<>();
        try {
            for (int i = 0; i < writers; i++) {
                final int writer = i;
                pending.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    for (int event = 0; event < eventsPerWriter; event++) {
                                        nodes.get(writer % nodes.size())
                                                .append(
                                                        sessionId,
                                                        "item.delta",
                                                        Map.of("writer", writer, "event", event));
                                    }
                                    return null;
                                }));
            }
            start.countDown();
            for (Future<?> future : pending) future.get(60, TimeUnit.SECONDS);
            var events = repository.findBySessionIdOrderBySeqAsc(sessionId);
            assertThat(events).hasSize(1 + writers * eventsPerWriter);
            for (int i = 0; i < events.size(); i++)
                assertThat(events.get(i).getSeq()).isEqualTo(i + 1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void commandSeesLatestSequenceAfterAnEarlierAdmissionRead() {
        String session = "ses_outer_snapshot";
        eventLog.append(session, "run.started", Map.of());
        var pool = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            status -> {
                                assertThat(repository.maxSeq(session)).isEqualTo(1L);
                                try {
                                    assertThat(
                                                    pool.submit(
                                                                    () ->
                                                                            eventLog.append(
                                                                                    session,
                                                                                    "item.delta",
                                                                                    Map.of()))
                                                            .get(10, TimeUnit.SECONDS)
                                                            .seq())
                                            .isEqualTo(2L);
                                } catch (Exception error) {
                                    throw new RuntimeException(error);
                                }
                                assertThat(
                                                eventLog.appendCommand(
                                                                session,
                                                                "turn.accepted",
                                                                Map.of(),
                                                                "snapshot-command")
                                                        .seq())
                                        .isEqualTo(3L);
                            });
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void idempotentReplayInAnEnclosingTransactionReturnsTheCommittedEvent() {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            var first =
                                    eventLog.appendIdempotent(
                                            "ses_replay",
                                            "item.delta",
                                            Map.of("text", "same"),
                                            "stable-replay-id");
                            var replay =
                                    eventLog.appendIdempotent(
                                            "ses_replay",
                                            "item.delta",
                                            Map.of("text", "same"),
                                            "stable-replay-id");
                            assertThat(replay).isEqualTo(first);
                        });
        assertThat(repository.findBySessionIdOrderBySeqAsc("ses_replay")).hasSize(1);
        assertThat(eventLog.append("ses_replay", "item.delta", Map.of()).seq()).isEqualTo(2L);
    }

    @Test
    void commandAdmissionDoesNotBlockAnUnrelatedNewSession() {
        eventLog.append("zz-review-b", "run.started", Map.of());
        var pool = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            status -> {
                                eventLog.appendCommand(
                                        "zz-review-b", "turn.accepted", Map.of(), "held-command");
                                try {
                                    assertThat(
                                                    pool.submit(
                                                                    () ->
                                                                            eventLog.append(
                                                                                    "zz-review-a",
                                                                                    "run.started",
                                                                                    Map.of()))
                                                            .get(5, TimeUnit.SECONDS)
                                                            .seq())
                                            .isEqualTo(1L);
                                } catch (Exception error) {
                                    throw new RuntimeException(error);
                                }
                            });
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentFirstCommandsBothCommit() throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(8);
        var pending = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < 8; i++) {
                final int command = i;
                pending.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return new TransactionTemplate(transactionManager)
                                            .execute(
                                                    status ->
                                                            eventLog.appendCommand(
                                                                    "ses_first_commands",
                                                                    "turn.accepted",
                                                                    Map.of("command", command),
                                                                    "first-command-" + command));
                                }));
            }
            start.countDown();
            for (var future : pending) future.get(20, TimeUnit.SECONDS);
            assertThat(repository.findBySessionIdOrderBySeqAsc("ses_first_commands"))
                    .extracting(
                            io.agentscope.builder.web.persistence.jpa.SessionEventEntity::getSeq)
                    .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void existingTranscriptInitializesCounterAtItsHighWatermark() {
        new JdbcTemplate(dataSource)
                .update(
                        "insert into builder_session_event (session_id, event_id, seq, event_type,"
                                + " payload_json, created_at) values (?, ?, ?, ?, ?, ?)",
                        "ses_legacy",
                        "legacy-event",
                        17L,
                        "run.started",
                        "{}",
                        1L);
        assertThat(
                        eventLog.appendIdempotent(
                                        "ses_legacy", "run.started", Map.of(), "legacy-event")
                                .seq())
                .isEqualTo(17L);
        assertThat(eventLog.append("ses_legacy", "item.delta", Map.of()).seq()).isEqualTo(18L);
        assertThat(eventLog.append("ses_legacy", "item.delta", Map.of()).seq()).isEqualTo(19L);
    }

    @Test
    void idempotentReplaySeesTheWinnerAfterAnEarlierSnapshotRead() {
        var pool = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            status -> {
                                assertThat(repository.findByEventId("snapshot-replay-id"))
                                        .isEmpty();
                                SessionEventDto winner;
                                try {
                                    winner =
                                            pool.submit(
                                                            () ->
                                                                    eventLog.appendIdempotent(
                                                                            "ses_snapshot_replay",
                                                                            "item.delta",
                                                                            Map.of("text", "same"),
                                                                            "snapshot-replay-id"))
                                                    .get(10, TimeUnit.SECONDS);
                                } catch (Exception error) {
                                    throw new RuntimeException(error);
                                }
                                assertThat(
                                                eventLog.appendIdempotent(
                                                        "ses_snapshot_replay",
                                                        "item.delta",
                                                        Map.of("text", "same"),
                                                        "snapshot-replay-id"))
                                        .isEqualTo(winner);
                            });
            assertThat(eventLog.append("ses_snapshot_replay", "item.delta", Map.of()).seq())
                    .isEqualTo(2L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentIdempotentAppendsShareOneSequence() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        var pending = new ArrayList<Future<SessionEventDto>>();
        try {
            for (int i = 0; i < 8; i++) {
                pending.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return eventLog.appendIdempotent(
                                            "ses_idempotent_race",
                                            "item.delta",
                                            Map.of("text", "same"),
                                            "concurrent-stable-id");
                                }));
            }
            start.countDown();
            for (var future : pending)
                assertThat(future.get(20, TimeUnit.SECONDS).seq()).isEqualTo(1L);
            assertThat(repository.findBySessionIdOrderBySeqAsc("ses_idempotent_race")).hasSize(1);
            assertThat(eventLog.append("ses_idempotent_race", "item.delta", Map.of()).seq())
                    .isEqualTo(2L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rejectedReplayDoesNotChangeThePayloadOrAdvanceTheCounter() {
        var original =
                eventLog.appendIdempotent(
                        "ses_invalid_replay",
                        "item.delta",
                        Map.of("text", "original"),
                        "invalid-replay-id");
        assertThatThrownBy(
                        () ->
                                eventLog.appendIdempotent(
                                        "ses_invalid_replay",
                                        "item.delta",
                                        Map.of("text", "changed"),
                                        "invalid-replay-id"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(
                        () ->
                                eventLog.appendIdempotent(
                                        "ses_other_owner",
                                        "item.delta",
                                        Map.of("text", "original"),
                                        "invalid-replay-id"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(eventLog.getByEventId("invalid-replay-id")).isEqualTo(original);
        assertThat(eventLog.append("ses_invalid_replay", "item.delta", Map.of()).seq())
                .isEqualTo(2L);
    }

    @Test
    void mirrorFailureRollsBackTheFirstEventAndItsCounter() {
        var observedNotifier = mock(SessionEventNotifier.class);
        var node =
                new SessionEventLog(
                        repository,
                        jsonHelper,
                        new TransactionTemplate(transactionManager),
                        deletedSessions,
                        observedNotifier,
                        List.of(
                                (event, scope) -> {
                                    throw new IllegalStateException("outbox unavailable");
                                }),
                        60000L);
        assertThatThrownBy(
                        () ->
                                node.appendIdempotent(
                                        "ses_mirror_failure",
                                        "item.delta",
                                        Map.of(),
                                        "failed-mirror"))
                .hasMessage("outbox unavailable");
        assertThat(repository.findByEventId("failed-mirror")).isEmpty();
        verifyNoInteractions(observedNotifier);
        assertThat(eventLog.append("ses_mirror_failure", "item.delta", Map.of()).seq())
                .isEqualTo(1L);
    }

    @Test
    void appendNotifiesOnlyAfterTheAdmissionCommits() {
        var observedNotifier = mock(SessionEventNotifier.class);
        var node =
                new SessionEventLog(
                        repository,
                        jsonHelper,
                        new TransactionTemplate(transactionManager),
                        deletedSessions,
                        observedNotifier,
                        60000L);
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            node.append("ses_notify_commit", "item.delta", Map.of());
                            verifyNoInteractions(observedNotifier);
                        });
        verify(observedNotifier).publish("ses_notify_commit");
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            node.append("ses_notify_rollback", "item.delta", Map.of());
                            status.setRollbackOnly();
                        });
        verify(observedNotifier, org.mockito.Mockito.never()).publish("ses_notify_rollback");
    }

    private SessionEventLog node(SessionEventEntityRepository eventRepository) {
        return new SessionEventLog(
                eventRepository,
                jsonHelper,
                new TransactionTemplate(transactionManager),
                deletedSessions,
                notifier,
                60_000L);
    }

    @Test
    void commandAppendStillRollsBackWithItsAdmissionTransaction() {
        String sessionId = "ses_command_rollback";
        eventLog.append(sessionId, "run.started", Map.of());
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            assertThat(
                                            eventLog.appendCommand(
                                                            sessionId,
                                                            "input.accepted",
                                                            Map.of(),
                                                            "command-event")
                                                    .seq())
                                    .isEqualTo(2L);
                            status.setRollbackOnly();
                        });
        assertThat(repository.findByEventId("command-event")).isEmpty();
        assertThat(eventLog.append(sessionId, "item.delta", Map.of()).seq()).isEqualTo(2L);
    }

    @Test
    void concurrentAppendProducesUniqueSeq() throws Exception {
        String sessionId = "ses_concurrent";
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        AtomicReference<Throwable> error = new AtomicReference<>();
        for (int i = 0; i < writers; i++) {
            final int idx = i;
            pool.submit(
                    () -> {
                        try {
                            start.await();
                            eventLog.append(sessionId, "agent.message", Map.of("text", "m" + idx));
                        } catch (Throwable t) {
                            error.compareAndSet(null, t);
                        } finally {
                            done.countDown();
                        }
                    });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(error.get()).isNull();
        assertThat(repository.findBySessionIdOrderBySeqAsc(sessionId)).hasSize(writers);
        assertThat(repository.maxSeq(sessionId)).isEqualTo(writers);
    }

    @Test
    void subscribeSeesEventsAppendedAfterSubscription() {
        String sessionId = "ses_subscribe";
        eventLog.append(sessionId, "session.status_created", Map.of("status", "created"));

        StepVerifier.create(eventLog.subscribe(sessionId, 0L).take(2))
                .thenAwait(Duration.ofMillis(150))
                .then(
                        () ->
                                eventLog.append(
                                        sessionId, "session.status_idle", Map.of("status", "idle")))
                .expectNextMatches(e -> "session.status_created".equals(e.type()))
                .expectNextMatches(e -> "session.status_idle".equals(e.type()))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void subscribeAfterCursorSkipsEarlierEvents() {
        String sessionId = "ses_cursor";
        SessionEventDto first =
                eventLog.append(sessionId, "session.status_created", Map.of("status", "created"));

        StepVerifier.create(eventLog.subscribe(sessionId, first.seq()).take(1))
                .thenAwait(Duration.ofMillis(150))
                .then(() -> eventLog.append(sessionId, "user.message", Map.of("text", "hi")))
                .expectNextMatches(e -> "user.message".equals(e.type()) && e.seq() > first.seq())
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    // A turn keeps running for seconds after its session is deleted, so the purge has to
    // reject those late appends or they recreate the rows it just removed.
    @Test
    void purgedSessionDropsLateAppends() {
        String sessionId = "ses_purged";
        eventLog.append(sessionId, "agent.message", Map.of("text", "before"));
        assertThat(repository.findBySessionIdOrderBySeqAsc(sessionId)).hasSize(1);

        eventLog.purgeDeletedSession(sessionId);
        SessionEventDto dropped =
                eventLog.append(sessionId, "agent.message", Map.of("text", "after"));

        assertThat(deletedSessions.isDeleted(sessionId)).isTrue();
        assertThat(dropped.seq()).isEqualTo(-1L);
        assertThat(repository.findBySessionIdOrderBySeqAsc(sessionId)).isEmpty();
    }

    @Test
    void clearingTranscriptKeepsTheSessionWritable() {
        String sessionId = "ses_cleared";
        eventLog.append(sessionId, "agent.message", Map.of("text", "old"));

        eventLog.deleteBySessionId(sessionId);
        eventLog.append(sessionId, "agent.message", Map.of("text", "new"));

        assertThat(deletedSessions.isDeleted(sessionId)).isFalse();
        assertThat(repository.findBySessionIdOrderBySeqAsc(sessionId)).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void clearingSessionDoesNotBlockAnotherSession(boolean purge, boolean populated)
            throws Exception {
        String prefix = "ses_clear_concurrency_" + UUID.randomUUID() + "_";
        String sessionId = prefix + "a";
        String otherSession = prefix + "b";
        if (populated) eventLog.append(sessionId, "item.delta", Map.of());
        var beforeCommit = new CountDownLatch(1);
        var releaseCommit = new CountDownLatch(1);
        var heldRepository =
                (SessionEventEntityRepository)
                        Proxy.newProxyInstance(
                                SessionEventEntityRepository.class.getClassLoader(),
                                new Class<?>[] {SessionEventEntityRepository.class},
                                (proxy, method, arguments) -> {
                                    Object result;
                                    try {
                                        result = method.invoke(repository, arguments);
                                    } catch (InvocationTargetException error) {
                                        throw error.getCause();
                                    }
                                    if (method.getName().equals("deleteBySessionId")) {
                                        assertThat(
                                                        TransactionSynchronizationManager
                                                                .isActualTransactionActive())
                                                .isTrue();
                                        var connection = DataSourceUtils.getConnection(dataSource);
                                        try {
                                            assertThat(connection.getTransactionIsolation())
                                                    .isEqualTo(
                                                            Connection.TRANSACTION_READ_COMMITTED);
                                        } finally {
                                            DataSourceUtils.releaseConnection(
                                                    connection, dataSource);
                                        }
                                        TransactionSynchronizationManager.registerSynchronization(
                                                new TransactionSynchronization() {
                                                    @Override
                                                    public void beforeCommit(boolean readOnly) {
                                                        beforeCommit.countDown();
                                                        try {
                                                            assertThat(
                                                                            releaseCommit.await(
                                                                                    15,
                                                                                    TimeUnit
                                                                                            .SECONDS))
                                                                    .isTrue();
                                                        } catch (InterruptedException error) {
                                                            Thread.currentThread().interrupt();
                                                            throw new IllegalStateException(error);
                                                        }
                                                    }
                                                });
                                    }
                                    return result;
                                });
        SessionEventLog clearingNode = node(heldRepository);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> clearing =
                    pool.submit(
                            () -> {
                                if (purge) clearingNode.purgeDeletedSession(sessionId);
                                else clearingNode.deleteBySessionId(sessionId);
                            });
            assertThat(beforeCommit.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(
                            pool.submit(() -> eventLog.append(otherSession, "item.delta", Map.of()))
                                    .get(5, TimeUnit.SECONDS)
                                    .seq())
                    .isEqualTo(1L);
            releaseCommit.countDown();
            clearing.get(10, TimeUnit.SECONDS);
            assertThat(repository.findBySessionIdOrderBySeqAsc(sessionId)).isEmpty();
            assertThat(repository.findBySessionIdOrderBySeqAsc(otherSession)).hasSize(1);
            assertThat(deletedSessions.isDeleted(sessionId)).isEqualTo(purge);
        } finally {
            releaseCommit.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) pool.shutdownNow();
        }
    }

    @Test
    void clearingTranscriptRemovesIdentitiesAndRollbackRestoresThem() {
        String sessionId = "ses_clear_identity";
        var original =
                eventLog.appendIdempotent(
                        sessionId, "item.delta", Map.of("text", "old"), "clear-id");
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            eventLog.deleteBySessionId(sessionId);
                            status.setRollbackOnly();
                        });
        assertThat(
                        eventLog.appendIdempotent(
                                sessionId, "item.delta", Map.of("text", "old"), "clear-id"))
                .isEqualTo(original);
        eventLog.deleteBySessionId(sessionId);
        assertThat(
                        eventLog.appendIdempotent(
                                        sessionId, "item.delta", Map.of("text", "new"), "clear-id")
                                .seq())
                .isEqualTo(1L);
    }

    @Test
    void clearingLegacyTranscriptUsesConstantStatementsWithoutBackfillingIdentities() {
        var jdbc = new JdbcTemplate(dataSource);
        for (int size : List.of(1, 1000)) {
            var rows = new ArrayList<Object[]>();
            String sessionId = "ses_legacy_clear_" + size;
            for (int i = 1; i <= size; i++)
                rows.add(new Object[] {sessionId, sessionId + "_event_" + i, (long) i});
            jdbc.batchUpdate(
                    "insert into builder_session_event (session_id, event_id, seq, event_type,"
                            + " payload_json, created_at) values (?, ?, ?, 'item.delta', '{}', 1)",
                    rows);
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean statisticsEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            long beforeSmall = statistics.getPrepareStatementCount();
            eventLog.deleteBySessionId("ses_legacy_clear_1");
            long smallCount = statistics.getPrepareStatementCount() - beforeSmall;
            long beforeLarge = statistics.getPrepareStatementCount();
            eventLog.deleteBySessionId("ses_legacy_clear_1000");
            long largeCount = statistics.getPrepareStatementCount() - beforeLarge;
            assertThat(smallCount).isPositive();
            assertThat(largeCount).isLessThanOrEqualTo(smallCount);
        } finally {
            statistics.setStatisticsEnabled(statisticsEnabled);
        }
        for (int size : List.of(1, 1000)) {
            String sessionId = "ses_legacy_clear_" + size;
            assertThat(repository.findBySessionIdOrderBySeqAsc(sessionId)).isEmpty();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from builder_session_event_identity where"
                                            + " session_id = ?",
                                    Long.class,
                                    sessionId))
                    .isZero();
            assertThat(
                            eventLog.appendIdempotent(
                                            sessionId,
                                            "item.delta",
                                            Map.of("text", "new"),
                                            sessionId + "_event_1")
                                    .seq())
                    .isEqualTo(1L);
        }
    }

    @Test
    void rolledBackLegacyClearPreservesHistoryAndSerializesConcurrentAppend() throws Exception {
        String sessionId = "ses_legacy_clear_rollback";
        new JdbcTemplate(dataSource)
                .update(
                        "insert into builder_session_event (session_id, event_id, seq, event_type,"
                                + " payload_json, created_at) values (?, ?, ?, ?, ?, ?)",
                        sessionId,
                        "legacy-clear-rollback-event",
                        17L,
                        "run.started",
                        "{}",
                        1L);
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<SessionEventDto> append;
            var held = transactionManager.getTransaction(new DefaultTransactionDefinition());
            try {
                eventLog.deleteBySessionId(sessionId);
                CountDownLatch started = new CountDownLatch(1);
                append =
                        pool.submit(
                                () -> {
                                    started.countDown();
                                    return eventLog.append(sessionId, "item.delta", Map.of());
                                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> append.get(300, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                transactionManager.rollback(held);
            }
            assertThat(append.get(10, TimeUnit.SECONDS).seq()).isEqualTo(18L);
            assertThat(
                            eventLog.appendIdempotent(
                                            sessionId,
                                            "run.started",
                                            Map.of(),
                                            "legacy-clear-rollback-event")
                                    .seq())
                    .isEqualTo(17L);
            assertThat(repository.findBySessionIdOrderBySeqAsc(sessionId)).hasSize(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        ManagedJsonHelper managedJsonHelper(ObjectMapper objectMapper) {
            return new ManagedJsonHelper(objectMapper);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
            return new TransactionTemplate(txManager);
        }

        @Bean
        DeletedSessionRegistry deletedSessionRegistry() {
            return new DeletedSessionRegistry();
        }

        @Bean
        SessionEventNotifier sessionEventNotifier(DataSource dataSource) {
            return new SessionEventNotifier(dataSource, 100L);
        }

        @Bean
        SessionEventLog sessionEventLog(
                SessionEventEntityRepository repository,
                ManagedJsonHelper jsonHelper,
                TransactionTemplate transactionTemplate,
                DeletedSessionRegistry deletedSessions,
                SessionEventNotifier notifier) {
            return new SessionEventLog(
                    repository,
                    jsonHelper,
                    transactionTemplate,
                    deletedSessions,
                    notifier,
                    60_000L);
        }
    }
}
