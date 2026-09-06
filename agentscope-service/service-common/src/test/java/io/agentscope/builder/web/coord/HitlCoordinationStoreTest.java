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
package io.agentscope.builder.web.coord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.builder.BuilderCommonTestApp;
import io.agentscope.builder.web.persistence.jpa.CoordHitlTicketEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordLeaseEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordWorkItemEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordWorkerHeartbeatEntityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@ContextConfiguration(classes = BuilderCommonTestApp.class)
@Import(HitlCoordinationStoreTest.Config.class)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:hitlStore;DB_CLOSE_DELAY=-1;MODE=MYSQL",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
class HitlCoordinationStoreTest {

    @Autowired CoordinationStore store;

    @Test
    void ticketsAreSessionScopedImmutableAndFullyFenced() {
        long now = System.currentTimeMillis();
        CoordinationStore.HitlTicket first = ticket("session-a", "tool-a", "input-a", now);
        CoordinationStore.HitlTicket second = ticket("session-b", "tool-a", "input-b", now);
        assertThat(store.putHitlTicket(first)).isEqualTo(first);
        assertThat(store.putHitlTicket(second)).isEqualTo(second);
        assertThat(store.getHitlTicket(fence(first, first.attemptId()))).contains(first);
        assertThat(store.getHitlTicket(fence(second, second.attemptId()))).contains(second);

        assertThatThrownBy(
                        () ->
                                store.putHitlTicket(
                                        ticket("session-a", "tool-a", "changed-input", now)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        store.resolveHitlTicket(
                                fence(first, "old-attempt"), "approved", 2, true, null, now + 10))
                .isEmpty();

        var resolved =
                store.resolveHitlTicket(
                        fence(first, first.attemptId()),
                        "approved",
                        2,
                        true,
                        null,
                        first.expiresAt() + 10);
        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().changed()).isTrue();
        assertThat(
                        store.resolveHitlTicket(
                                fence(first, first.attemptId()),
                                "approved",
                                2,
                                true,
                                null,
                                first.expiresAt() + 20))
                .get()
                .extracting(CoordinationStore.HitlResolution::changed)
                .isEqualTo(false);
        assertThat(
                        store.resolveHitlTicket(
                                fence(first, first.attemptId()),
                                "rejected",
                                3,
                                false,
                                "no",
                                first.expiresAt() + 20))
                .isEmpty();
        assertThat(store.markHitlContinuationReady(fence(first, first.attemptId()))).isTrue();
        assertThat(store.getHitlTicket(fence(first, first.attemptId())))
                .get()
                .extracting(CoordinationStore.HitlTicket::continuationReady)
                .isEqualTo(true);
    }

    private static CoordinationStore.HitlTicket ticket(
            String sessionId, String toolUseId, String input, long now) {
        return new CoordinationStore.HitlTicket(
                toolUseId,
                sessionId,
                "owner-a",
                "approval-" + sessionId,
                "task-a",
                "attempt-a",
                2,
                "turn-a",
                "lease-a",
                "web_search",
                input,
                null,
                0,
                null,
                null,
                now,
                now + 100,
                null,
                false);
    }

    private static CoordinationStore.HitlDecisionFence fence(
            CoordinationStore.HitlTicket ticket, String attemptId) {
        return new CoordinationStore.HitlDecisionFence(
                ticket.sessionId(),
                ticket.toolUseId(),
                ticket.approvalId(),
                ticket.agentTaskId(),
                attemptId,
                ticket.dispatchGeneration(),
                ticket.turnId());
    }

    @TestConfiguration
    static class Config {
        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
            return new TransactionTemplate(txManager);
        }

        @Bean
        CoordinationStore coordinationStore(
                CoordLeaseEntityRepository leaseRepository,
                CoordHitlTicketEntityRepository hitlRepository,
                CoordWorkItemEntityRepository workRepository,
                CoordWorkerHeartbeatEntityRepository workerRepository,
                TransactionTemplate transactionTemplate) {
            return new JdbcCoordinationStore(
                    leaseRepository,
                    hitlRepository,
                    workRepository,
                    workerRepository,
                    transactionTemplate);
        }
    }
}
