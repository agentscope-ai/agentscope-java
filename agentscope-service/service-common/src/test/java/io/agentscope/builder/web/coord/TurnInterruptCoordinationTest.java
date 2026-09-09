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

import io.agentscope.builder.BuilderCommonTestApp;
import io.agentscope.builder.web.persistence.jpa.CoordHitlTicketEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordLeaseEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordWorkItemEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordWorkerHeartbeatEntityRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@ContextConfiguration(classes = BuilderCommonTestApp.class)
@Import(TurnInterruptCoordinationTest.Config.class)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:turnInterrupt;DB_CLOSE_DELAY=-1;MODE=MYSQL",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
class TurnInterruptCoordinationTest {

    @Autowired CoordinationStore coordinationStore;

    @Test
    void requestAndConsumeTurnInterrupt() {
        coordinationStore.requestTurnInterrupt("ses_x", "user.interrupt");
        Optional<String> first = coordinationStore.consumeTurnInterrupt("ses_x");
        assertThat(first).contains("user.interrupt");
        assertThat(coordinationStore.consumeTurnInterrupt("ses_x")).isEmpty();
    }

    @Test
    void fencedInterruptPreservesAttemptToken() {
        coordinationStore.requestFencedTurnInterrupt("ses_fenced", "attempt-a", "fence-a");
        coordinationStore.requestFencedTurnInterrupt("ses_fenced", "attempt-b", "fence-b");
        coordinationStore.requestTurnInterrupt("ses_fenced", "user.interrupt");

        assertThat(coordinationStore.consumeTurnInterruptRequest("ses_fenced"))
                .contains(new CoordinationStore.TurnInterruptRequest("user.interrupt", null));
        var remaining =
                List.of(
                        coordinationStore.consumeTurnInterruptRequest("ses_fenced").orElseThrow(),
                        coordinationStore.consumeTurnInterruptRequest("ses_fenced").orElseThrow());
        assertThat(remaining)
                .containsExactlyInAnyOrder(
                        new CoordinationStore.TurnInterruptRequest("attempt-a", "fence-a"),
                        new CoordinationStore.TurnInterruptRequest("attempt-b", "fence-b"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void expiredTurnLeaseHasOnlyOneTakeoverWinner() throws Exception {
        coordinationStore.tryAcquireTurnLease("ses_race", "owner", "old-owner", Duration.ZERO);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<CoordinationStore.LeaseHandle>> first =
                    executor.submit(
                            () -> {
                                start.await();
                                return coordinationStore.tryAcquireTurnLease(
                                        "ses_race", "owner", "new-owner-a", Duration.ofSeconds(30));
                            });
            Future<Optional<CoordinationStore.LeaseHandle>> second =
                    executor.submit(
                            () -> {
                                start.await();
                                return coordinationStore.tryAcquireTurnLease(
                                        "ses_race", "owner", "new-owner-b", Duration.ofSeconds(30));
                            });
            start.countDown();
            List<Optional<CoordinationStore.LeaseHandle>> results =
                    List.of(first.get(), second.get());

            assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
            String winner =
                    results.stream()
                            .flatMap(Optional::stream)
                            .findFirst()
                            .orElseThrow()
                            .instanceId();
            coordinationStore.requestTurnInterrupt("ses_race", "user.interrupt");
            assertThat(
                            coordinationStore.heartbeatTurnLease(
                                    "ses_race", "old-owner", Duration.ofSeconds(30)))
                    .isFalse();
            assertThat(coordinationStore.consumeTurnInterrupt("ses_race"))
                    .contains("user.interrupt");
            assertThat(coordinationStore.getTurnLease("ses_race"))
                    .get()
                    .extracting(CoordinationStore.LeaseHandle::instanceId)
                    .isEqualTo(winner);
        } finally {
            executor.shutdownNow();
        }
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
