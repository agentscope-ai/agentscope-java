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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.rocketmq.client.java.impl.consumer.ManagedLitePushConsumer.Ack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs the same durable capture, terminal rollback and multi-replica claim assertions on PostgreSQL. */
@EnabledIfEnvironmentVariable(named = "MANAGED_DATABASE_TEST_URI", matches = ".+")
class ManagedPostgresTurnAcknowledgementsIntegrationTest extends ManagedTurnAcknowledgementsTest {
    @Test
    void concurrentExpiryCleanersSkipLockedReceiptsAndPreserveTheFreshCapability()
            throws Exception {
        var owner =
                replica(
                        r -> {
                            fail("A waiting Turn must not be acknowledged");
                            return Ack.RETRY;
                        });
        for (int index = 0; index < 12; index++)
            owner.suspended(command, receipt("expired-" + index), Duration.ofMillis(1));
        owner.suspended(command, receipt("fresh"), Duration.ofSeconds(60));
        clock.set(2000);
        var peers = Executors.newFixedThreadPool(2);
        try {
            new TransactionTemplate(new DataSourceTransactionManager(source))
                    .executeWithoutResult(
                            tx -> {
                                jdbc.queryForObject(
                                        "SELECT receipt_id FROM ma.turn_ack_receipt WHERE"
                                            + " scope_key=? AND expires_at<=? ORDER BY receipt_id"
                                            + " LIMIT 1 FOR UPDATE",
                                        String.class,
                                        scope.messageGroup(),
                                        clock.get());
                                var start = new CountDownLatch(1);
                                var first =
                                        peers.submit(
                                                () -> {
                                                    assertTrue(start.await(2, TimeUnit.SECONDS));
                                                    return replica(r -> Ack.RETRY).retry(100);
                                                });
                                var second =
                                        peers.submit(
                                                () -> {
                                                    assertTrue(start.await(2, TimeUnit.SECONDS));
                                                    return replica(r -> Ack.RETRY).retry(100);
                                                });
                                start.countDown();
                                try {
                                    assertEquals(0, first.get(2, TimeUnit.SECONDS));
                                    assertEquals(0, second.get(2, TimeUnit.SECONDS));
                                } catch (Exception failure) {
                                    throw new AssertionError(
                                            "Expiry cleanup must skip another replica's locked"
                                                    + " receipt",
                                            failure);
                                }
                                assertEquals(2, rows());
                            });
            assertEquals(0, owner.retry(100));
            assertEquals(1, rows());
            assertEquals(
                    receipt("fresh").version("brain"),
                    jdbc.queryForObject(
                            "SELECT receipt_id FROM ma.turn_ack_receipt WHERE scope_key=?",
                            String.class,
                            scope.messageGroup()));
        } finally {
            peers.shutdownNow();
        }
    }

    @Override
    protected DataSource database() {
        return new DriverManagerDataSource(
                System.getenv("MANAGED_DATABASE_TEST_URI"), "managed", "managed-local-test");
    }
}
