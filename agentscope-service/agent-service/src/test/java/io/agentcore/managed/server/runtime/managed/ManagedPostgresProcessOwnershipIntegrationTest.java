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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

@EnabledIfEnvironmentVariable(named = "MANAGED_DATABASE_TEST_URI", matches = ".+")
class ManagedPostgresProcessOwnershipIntegrationTest extends ManagedProcessOwnershipTest {
    private ManagedSchemaMigrationIntegrationTest.TemporaryDatabase temporary;

    @Override
    protected DataSource database() throws Exception {
        temporary = new ManagedSchemaMigrationIntegrationTest.TemporaryDatabase();
        try (var connection = temporary.connect()) {
            return new DriverManagerDataSource(
                    connection.getMetaData().getURL(), "managed", "managed-local-test");
        }
    }

    @AfterEach
    void closeDatabase() throws Exception {
        if (temporary != null) temporary.close();
    }

    @Test
    void replayingOwnClaimDoesNotUpgradeTheLeaseLockAndBlockRenewal() throws Exception {
        var source = database();
        ManagedAdmissionStore.initialize(source);
        var admission = new ManagedAdmissionStore(source, new ObjectMapper());
        var scope = new ManagedScope("t", "n", "renewal");
        admission.createSession(scope, "{}");
        admission.queue().enable(scope);
        var turn = admission.acceptTurn(scope, "agent", "key", "{}");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try (var owner =
                new ManagedProcessOwnership(
                        source,
                        "brain",
                        Duration.ofSeconds(60),
                        Duration.ofMinutes(10),
                        Duration.ofHours(1))) {
            admission.processOwnership(owner);
            admission.activate(scope, turn.id(), turn.publicationSequence(), "token");
            var jdbc = new JdbcTemplate(source);
            long before =
                    jdbc.queryForObject(
                            "SELECT expires_at FROM ma_worker.lease WHERE incarnation=?",
                            Long.class,
                            owner.incarnation());
            var replay =
                    pool.submit(
                            () ->
                                    owner.transaction(
                                            () -> {
                                                admission.activate(
                                                        scope,
                                                        turn.id(),
                                                        turn.publicationSequence(),
                                                        "token");
                                                entered.countDown();
                                                try {
                                                    if (!release.await(5, TimeUnit.SECONDS))
                                                        throw new IllegalStateException(
                                                                "fixture timeout");
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                    throw new IllegalStateException(e);
                                                }
                                                return true;
                                            }));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                pool.submit(() -> ReflectionTestUtils.invokeMethod(owner, "renew"))
                        .get(2, TimeUnit.SECONDS);
                assertTrue(
                        jdbc.queryForObject(
                                        "SELECT expires_at FROM ma_worker.lease WHERE"
                                                + " incarnation=?",
                                        Long.class,
                                        owner.incarnation())
                                > before);
            } finally {
                release.countDown();
                replay.get(3, TimeUnit.SECONDS);
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
