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

import io.agentscope.builder.web.persistence.jpa.SessionEventFenceEntity;
import io.agentscope.builder.web.persistence.jpa.SessionEventFenceRepository;
import io.agentscope.builder.web.persistence.jpa.SessionEventOutboxEntity;
import io.agentscope.builder.web.persistence.jpa.SessionEventOutboxRepository;
import io.agentscope.builder.web.persistence.jpa.SessionExecutionScopeEntity;
import io.agentscope.builder.web.persistence.jpa.SessionExecutionScopeRepository;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;

class ManagedEventOutboxPersistenceTest {
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
