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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.managed.service.DeletedSessionRegistry;
import io.agentscope.builder.web.managed.service.ManagedJsonHelper;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.builder.web.managed.service.SessionEventNotifier;
import io.agentscope.builder.web.managed.service.SessionEventScope;
import io.agentscope.builder.web.persistence.jpa.SessionEventEntity;
import io.agentscope.builder.web.persistence.jpa.SessionEventEntityRepository;
import io.agentscope.builder.web.persistence.jpa.SessionEventFenceEntity;
import io.agentscope.builder.web.persistence.jpa.SessionEventFenceRepository;
import io.agentscope.builder.web.persistence.jpa.SessionEventOutboxEntity;
import io.agentscope.builder.web.persistence.jpa.SessionEventOutboxRepository;
import io.agentscope.builder.web.persistence.jpa.SessionExecutionScopeEntity;
import io.agentscope.builder.web.persistence.jpa.SessionExecutionScopeRepository;
import java.util.List;
import java.util.Map;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;

class ManagedEventOutboxPersistenceTest {
    @Test
    @SuppressWarnings("unchecked")
    void personalChatCommitsWithTheSourceLogAndDeliversInOrderAfterRestart() {
        try (var factory =
                new Configuration()
                        .addAnnotatedClass(SessionEventEntity.class)
                        .addAnnotatedClass(SessionEventOutboxEntity.class)
                        .addAnnotatedClass(SessionEventFenceEntity.class)
                        .addAnnotatedClass(SessionExecutionScopeEntity.class)
                        .setProperty(
                                "hibernate.connection.url",
                                "jdbc:h2:mem:chat-outbox;DB_CLOSE_DELAY=-1")
                        .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                        .buildSessionFactory()) {
            var repositories =
                    new JpaRepositoryFactory(
                            SharedEntityManagerCreator.createSharedEntityManager(factory));
            var events = repositories.getRepository(SessionEventOutboxRepository.class);
            var sources = repositories.getRepository(SessionEventEntityRepository.class);
            var scopes = repositories.getRepository(SessionExecutionScopeRepository.class);
            var fences = repositories.getRepository(SessionEventFenceRepository.class);
            var tx = new TransactionTemplate(new JpaTransactionManager(factory));
            var mapper = new ObjectMapper();
            var client = spy(new ControlPlaneClient("http://unused.invalid", "test", mapper));
            var outbox = new ManagedEventOutbox(events, scopes, fences, tx, mapper, client);
            var log =
                    new SessionEventLog(
                            sources,
                            new ManagedJsonHelper(mapper),
                            tx,
                            new DeletedSessionRegistry(),
                            mock(SessionEventNotifier.class),
                            List.of(new ControlPlaneSessionEventMirror(outbox)),
                            1000);
            outbox.beginAdmission("chat", "chat-admission", SessionEventScope.NONE);
            outbox.bindRun("chat-admission", "chat-run", 0);
            assertThatThrownBy(
                            () ->
                                    tx.executeWithoutResult(
                                            status -> {
                                                log.append(
                                                        "chat",
                                                        "message.created",
                                                        Map.of("text", "rolled back"),
                                                        "rolled-back");
                                                throw new IllegalStateException(
                                                        "source transaction failed");
                                            }))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(sources.findAll()).isEmpty();
            assertThat(events.findAll()).isEmpty();
            var user =
                    log.appendIdempotentScoped(
                            "chat",
                            "item.completed",
                            message("user", "hello"),
                            "user-event",
                            SessionEventScope.NONE);
            var assistant =
                    log.appendIdempotentScoped(
                            "chat",
                            "item.completed",
                            message("assistant", "reply"),
                            "assistant-event",
                            outbox.scopeForRun("chat", "chat-run").forNativeFact());
            log.appendIdempotentScoped(
                    "chat",
                    "item.completed",
                    assistant.payload(),
                    assistant.id(),
                    SessionEventScope.NONE.forNativeFact());
            assertThat(sources.count()).isEqualTo(2);
            assertThat(events.count()).isEqualTo(2);
            assertThat(fences.count()).isZero();
            assertThat(events.findDeliverable(System.currentTimeMillis(), PageRequest.of(0, 8)))
                    .extracting(event -> event.eventId)
                    .containsExactly(user.id());
            doThrow(new IllegalStateException("CP unavailable"))
                    .doNothing()
                    .when(client)
                    .sendSessionEventReport(anyString(), any());
            assertThat(outbox.deliver(user.id())).isFalse();
            assertThat(events.findDeliverable(System.currentTimeMillis(), PageRequest.of(0, 8)))
                    .isEmpty();
            tx.executeWithoutResult(
                    status -> {
                        var row = events.lockById(user.id()).orElseThrow();
                        row.nextAttemptAt = 0;
                        events.saveAndFlush(row);
                    });
            var restarted = new ManagedEventOutbox(events, scopes, fences, tx, mapper, client);
            assertThat(restarted.deliver(user.id())).isTrue();
            assertThat(events.findDeliverable(System.currentTimeMillis(), PageRequest.of(0, 8)))
                    .extracting(event -> event.eventId)
                    .containsExactly(assistant.id());
            assertThat(restarted.deliver(assistant.id())).isTrue();
            assertThat(events.findDeliverable(System.currentTimeMillis(), PageRequest.of(0, 8)))
                    .isEmpty();
            ArgumentCaptor<Map<String, Object>> reports = ArgumentCaptor.forClass(Map.class);
            verify(client, times(3)).sendSessionEventReport(anyString(), reports.capture());
            assertThat(reports.getAllValues())
                    .extracting(report -> report.get("type"))
                    .containsExactly("user.message", "user.message", "agent.message");
            assertThat(reports.getAllValues())
                    .extracting(report -> (String) ((Map<?, ?>) report.get("payload")).get("text"))
                    .containsExactly("hello", "hello", "reply");
        }
    }

    private static Map<String, Object> message(String role, String text) {
        return Map.of(
                "item",
                Map.of(
                        "id",
                        role + "-item",
                        "role",
                        role,
                        "content",
                        List.of(Map.of("type", "text", "text", text))));
    }

    @Test
    void rollbackLeavesNoDeliveryAndTheQueryOnlyClaimsTheEarliestPendingSessionEvent() {
        try (var factory =
                new Configuration()
                        .addAnnotatedClass(SessionEventOutboxEntity.class)
                        .addAnnotatedClass(SessionEventFenceEntity.class)
                        .addAnnotatedClass(SessionExecutionScopeEntity.class)
                        .setProperty(
                                "hibernate.connection.url",
                                "jdbc:h2:mem:mirror-outbox;DB_CLOSE_DELAY=-1")
                        .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                        .setProperty("hibernate.show_sql", "false")
                        .buildSessionFactory()) {
            var repositories =
                    new JpaRepositoryFactory(
                            SharedEntityManagerCreator.createSharedEntityManager(factory));
            var events = repositories.getRepository(SessionEventOutboxRepository.class);
            var scopes = repositories.getRepository(SessionExecutionScopeRepository.class);
            var fences = repositories.getRepository(SessionEventFenceRepository.class);
            var tx = new TransactionTemplate(new JpaTransactionManager(factory));
            tx.executeWithoutResult(
                    status -> {
                        var fence = new SessionEventFenceEntity();
                        fence.attemptId = "attempt";
                        fence.sessionId = "session";
                        fence.sealed = true;
                        fences.saveAndFlush(fence);
                    });
            Boolean sealed = tx.execute(status -> fences.lockById("attempt").orElseThrow().sealed);
            assertThat(sealed).isTrue();
            assertThatThrownBy(
                            () ->
                                    tx.executeWithoutResult(
                                            status -> {
                                                events.saveAndFlush(row("rolled-back", 1));
                                                throw new IllegalStateException(
                                                        "source transaction failed");
                                            }))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(events.findAll()).isEmpty();
            tx.executeWithoutResult(
                    status -> {
                        events.saveAndFlush(row("first", 2));
                        events.saveAndFlush(row("second", 3));
                    });
            assertThat(events.findDeliverable(10, PageRequest.of(0, 8)))
                    .extracting(event -> event.eventId)
                    .containsExactly("first");
            tx.executeWithoutResult(
                    status -> {
                        var first = events.lockById("first").orElseThrow();
                        first.deliveredAt = 9;
                        events.save(first);
                    });
            assertThat(events.findDeliverable(10, PageRequest.of(0, 8)))
                    .extracting(event -> event.eventId)
                    .containsExactly("second");
            assertThat(scopes.findByRunId("never-started")).isEmpty();
        }
    }

    private static SessionEventOutboxEntity row(String id, long sequence) {
        var row = new SessionEventOutboxEntity();
        row.eventId = id;
        row.sessionId = "session";
        row.attemptId = "attempt";
        row.eventSeq = sequence;
        row.reportJson = "{}";
        return row;
    }
}
