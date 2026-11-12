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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "MANAGED_DATABASE_TEST_URI", matches = ".+")
class ManagedPostgresWorkerLeaseStoreIntegrationTest extends ManagedWorkerLeaseStoreTest {
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
    void removeDatabase() throws Exception {
        if (temporary != null) temporary.close();
    }

    @Test
    void commitsAllowRenewalButRetirementWaitsForTheirTransaction() throws Exception {
        var lease = store.register("brain", "pod", ttl);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(3);
        try {
            var first =
                    pool.submit(
                            () ->
                                    store.whileValid(
                                            lease,
                                            () -> {
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
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(
                    pool.submit(() -> store.whileValid(lease, () -> true))
                            .get(3, TimeUnit.SECONDS));
            // A busy process must not block its own heartbeat behind all active commits.
            assertTrue(pool.submit(() -> store.renew(lease, ttl)).get(2, TimeUnit.SECONDS));
            var retired = pool.submit(() -> store.retire(lease));
            assertThrows(TimeoutException.class, () -> retired.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(first.get(3, TimeUnit.SECONDS));
            retired.get(3, TimeUnit.SECONDS);
            assertFalse(store.renew(lease, ttl));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
