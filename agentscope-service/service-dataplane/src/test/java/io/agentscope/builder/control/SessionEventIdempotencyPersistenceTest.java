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
package io.agentscope.builder.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.managed.service.DeletedSessionRegistry;
import io.agentscope.builder.web.managed.service.ManagedJsonHelper;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.builder.web.managed.service.SessionEventNotifier;
import io.agentscope.builder.web.managed.service.SessionEventScope;
import io.agentscope.builder.web.persistence.jpa.SessionEventEntityRepository;
import io.agentscope.builder.web.persistence.jpa.SessionEventFenceRepository;
import io.agentscope.builder.web.persistence.jpa.SessionEventOutboxRepository;
import io.agentscope.builder.web.persistence.jpa.SessionExecutionScopeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses H2 by default; JDBC overrides must point to a disposable regression database. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SessionEventIdempotencyPersistenceTest.Config.class)
@TestPropertySource(
        properties = {
            "spring.datasource.url=${agentscope.test.jdbc-url:jdbc:h2:mem:eventIdempotency;DB_CLOSE_DELAY=-1}",
            "spring.datasource.username=${agentscope.test.jdbc-username:sa}",
            "spring.datasource.password=${agentscope.test.jdbc-password:}",
            "spring.datasource.driver-class-name=${agentscope.test.jdbc-driver:org.h2.Driver}",
            "spring.jpa.properties.hibernate.default_schema=${agentscope.test.jdbc-schema:PUBLIC}",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionEventIdempotencyPersistenceTest {
    @Configuration
    @EntityScan(basePackages = "io.agentscope.builder.web.persistence.jpa")
    @EnableJpaRepositories(basePackages = "io.agentscope.builder.web.persistence.jpa")
    static class Config {}

    @Autowired SessionEventEntityRepository repository;
    @Autowired SessionEventOutboxRepository delivery;
    @Autowired SessionEventFenceRepository fences;
    @Autowired SessionExecutionScopeRepository scopes;
    @Autowired PlatformTransactionManager tm;
    private final String eventNamespace = UUID.randomUUID().toString();
    private ManagedEventOutbox outbox;
    private SessionEventLog log;
    private final SessionEventScope scope =
            new SessionEventScope("tenant", "task", "attempt", 1, "turn");

    @BeforeEach
    void prepare() {
        var tx = new TransactionTemplate(tm);
        tx.executeWithoutResult(
                status -> {
                    delivery.deleteAllInBatch();
                    scopes.deleteAllInBatch();
                    fences.deleteAllInBatch();
                    for (var event : repository.findAll())
                        repository.deleteBySessionId(event.getSessionId());
                });
        var mapper = new ObjectMapper();
        outbox =
                new ManagedEventOutbox(
                        delivery,
                        scopes,
                        fences,
                        tx,
                        mapper,
                        new ControlPlaneClient("http://unused.invalid", "test", mapper));
        log =
                new SessionEventLog(
                        repository,
                        new ManagedJsonHelper(mapper),
                        tx,
                        new DeletedSessionRegistry(),
                        mock(SessionEventNotifier.class),
                        List.of(new ControlPlaneSessionEventMirror(outbox)),
                        60000);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4}) // JDBC READ_COMMITTED and REPEATABLE_READ
    void replayDoesNotBlockOtherSessionEventIds(int isolation) {
        log.append("left", "item.delta", Map.of(), eventId("a-left"));
        log.append("middle", "item.delta", Map.of(), eventId("m-middle"));
        log.append("right", "item.delta", Map.of(), eventId("z-right"));
        var tx = new TransactionTemplate(tm);
        tx.setIsolationLevel(isolation);
        tx.executeWithoutResult(
                status -> {
                    log.appendIdempotent("middle", "item.delta", Map.of(), eventId("m-middle"));
                    appendIndependent(eventId("b-between"));
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4})
    void firstIdempotentInsertDoesNotLockMissingIds(int isolation) {
        log.append("left", "item.delta", Map.of(), eventId("a-left"));
        log.append("right", "item.delta", Map.of(), eventId("z-right"));
        var tx = new TransactionTemplate(tm);
        tx.setIsolationLevel(isolation);
        tx.executeWithoutResult(
                status -> {
                    log.appendIdempotent("middle", "item.delta", Map.of(), eventId("m-middle"));
                    appendIndependent(eventId("b-between"));
                });
    }

    // Keep the index ordering without reusing deleted keys from another test: InnoDB's
    // duplicate checks on unpurged deleted keys can gap-lock even an ordinary INSERT.
    private String eventId(String suffix) {
        return eventNamespace + "-" + suffix;
    }

    private void appendIndependent(String eventId) {
        appendIndependent(eventId, SessionEventScope.NONE);
    }

    private void appendIndependent(String eventId, SessionEventScope eventScope) {
        var worker = Executors.newSingleThreadExecutor();
        try {
            assertThat(
                            worker.submit(
                                            () ->
                                                    log.appendIdempotentScoped(
                                                            "other",
                                                            "item.delta",
                                                            Map.of(),
                                                            eventId,
                                                            eventScope))
                                    .get(3, TimeUnit.SECONDS)
                                    .seq())
                    .isEqualTo(1L);
        } catch (Exception error) {
            throw new RuntimeException(error);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void mirroredReplaySeesWinnerAfterOuterSnapshot() {
        replayAfterSnapshot(false);
    }

    @Test
    void mirroredReplayDoesNotBlockAnotherAttempt() {
        var otherScope = new SessionEventScope("tenant", "task", "other-attempt", 1, "other-turn");
        outbox.beginAdmission("middle", "admission", scope);
        outbox.beginAdmission("other", "other-admission", otherScope);
        log.appendIdempotentScoped("middle", "item.delta", Map.of(), eventId("m-middle"), scope);
        var tx = new TransactionTemplate(tm);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.executeWithoutResult(
                status -> {
                    log.appendIdempotentScoped(
                            "middle", "item.delta", Map.of(), eventId("m-middle"), scope);
                    appendIndependent(eventId("b-between"), otherScope);
                });
        assertThat(delivery.findAll()).hasSize(2);
    }

    @Test
    void concurrentSessionsCannotClaimTheSameEventIdentity() throws Exception {
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var results = new ArrayList<Future<Object>>();
            for (String session : List.of("first", "second")) {
                results.add(
                        workers.submit(
                                () -> {
                                    start.await();
                                    try {
                                        return log.appendIdempotent(
                                                session, "item.delta", Map.of(), "shared-id");
                                    } catch (IllegalStateException conflict) {
                                        return conflict;
                                    }
                                }));
            }
            start.countDown();
            var outcomes =
                    List.of(
                            results.get(0).get(5, TimeUnit.SECONDS),
                            results.get(1).get(5, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(SessionEventDto.class::isInstance).count())
                    .isEqualTo(1);
            assertThat(outcomes.stream().filter(IllegalStateException.class::isInstance).count())
                    .isEqualTo(1);
            assertThat(repository.findAll()).hasSize(1);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void sealedAttemptStillAllowsReplayAfterOuterSnapshot() {
        replayAfterSnapshot(true);
    }

    private void replayAfterSnapshot(boolean seal) {
        String session = "mirror-session";
        log.append(session, "run.started", Map.of(), "seed");
        var worker = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(tm)
                    .executeWithoutResult(
                            status -> {
                                assertThat(repository.maxSeq(session)).isEqualTo(1L);
                                try {
                                    worker.submit(
                                                    () -> {
                                                        var winner =
                                                                log.appendIdempotentScoped(
                                                                        session,
                                                                        "item.delta",
                                                                        Map.of(),
                                                                        "winner",
                                                                        scope);
                                                        if (seal) {
                                                            outbox.freezeAttemptIfFinished(
                                                                    session,
                                                                    scope.attemptId(),
                                                                    admissions -> true);
                                                            outbox.sealAttempt(
                                                                    session, scope.attemptId());
                                                        }
                                                        return winner;
                                                    })
                                            .get(5, TimeUnit.SECONDS);
                                } catch (Exception error) {
                                    throw new RuntimeException(error);
                                }
                                assertThat(
                                                log.appendIdempotentScoped(
                                                                session,
                                                                "item.delta",
                                                                Map.of(),
                                                                "winner",
                                                                scope)
                                                        .seq())
                                        .isEqualTo(2L);
                                assertThat(
                                                log.append(
                                                                session,
                                                                "item.delta",
                                                                Map.of(),
                                                                "after-replay")
                                                        .seq())
                                        .isEqualTo(3L);
                            });
        } finally {
            worker.shutdownNow();
        }
        assertThat(
                        delivery.findBySessionIdAndAttemptIdOrderByEventSeqAsc(
                                session, scope.attemptId()))
                .hasSize(1);
    }

    @Test
    void outboxReplayPreservesDeliveryProgressAndRejectsNewSealedEvents() {
        String session = "delivery-session";
        log.appendIdempotentScoped(session, "item.delta", Map.of(), "delivered", scope);
        new TransactionTemplate(tm)
                .executeWithoutResult(
                        status -> {
                            var row = delivery.lockById("delivered").orElseThrow();
                            row.deliveredAt = 42;
                            row.attempts = 3;
                        });
        log.appendIdempotentScoped(session, "item.delta", Map.of(), "delivered", scope);
        var delivered = delivery.findById("delivered").orElseThrow();
        assertThat(delivered.deliveredAt).isEqualTo(42);
        assertThat(delivered.attempts).isEqualTo(3);
        outbox.freezeAttemptIfFinished(session, scope.attemptId(), admissions -> true);
        outbox.sealAttempt(session, scope.attemptId());
        assertThatThrownBy(
                        () ->
                                log.appendIdempotentScoped(
                                        session, "item.delta", Map.of(), "late", scope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("permanently sealed");
        assertThat(repository.findByEventId("late")).isEmpty();
        assertThat(delivery.findById("late")).isEmpty();
        assertThat(log.append(session, "item.delta", Map.of()).seq()).isEqualTo(2L);
    }

    @Test
    void invalidInsertIsNotTreatedAsAnIdempotentReplay() {
        assertThatThrownBy(() -> log.appendIdempotent("invalid", null, Map.of(), "invalid-event"))
                .isInstanceOf(RuntimeException.class);
        assertThat(repository.findByEventId("invalid-event")).isEmpty();
        assertThat(log.appendIdempotent("invalid", "item.delta", Map.of(), "valid-event").seq())
                .isEqualTo(1L);
    }
}
