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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ManagedWorkerLeaseStoreTest {
    DataSource source;
    ManagedWorkerLeaseStore store;
    final Duration ttl = Duration.ofSeconds(30);

    protected DataSource database() throws Exception {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:worker-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    @BeforeEach
    void setup() throws Exception {
        source = database();
        ManagedWorkerLeaseStore.initialize(source);
        store = new ManagedWorkerLeaseStore(source);
    }

    @Test
    void expiryRepairPagesUseTheExpiryIndexAndStableTieBreaker() {
        var first = store.register("brain", "first", ttl);
        var second = store.register("hands", "second", ttl);
        var jdbc = new JdbcTemplate(source);
        jdbc.update(
                "UPDATE ma_worker.lease SET expires_at=1 WHERE incarnation=?", first.incarnation());
        jdbc.update(
                "UPDATE ma_worker.lease SET expires_at=2 WHERE incarnation=?",
                second.incarnation());
        var page = store.expired(0, "", 1);
        assertEquals(List.of(new ManagedWorkerLeaseStore.Expired(1, first.incarnation())), page);
        assertEquals(
                List.of(new ManagedWorkerLeaseStore.Expired(2, second.incarnation())),
                store.expired(page.get(0).expiresAt(), page.get(0).incarnation(), 1));
    }

    @Test
    void expiredIncarnationCannotRenewOrCommitEvenWithoutTakeover() {
        var lease = store.register("brain", "pod-1", ttl);
        assertTrue(store.renew(lease, ttl));
        assertEquals("committed", store.whileValid(lease, () -> "committed"));
        new JdbcTemplate(source)
                .update(
                        "UPDATE ma_worker.lease SET expires_at=0 WHERE incarnation=?",
                        lease.incarnation());
        assertFalse(store.renew(lease, ttl));
        assertThrows(
                ManagedStateException.class, () -> store.whileValid(lease, () -> "must not run"));
        assertEquals(
                List.of(lease.incarnation()),
                store.expired(0, "", 10).stream()
                        .map(ManagedWorkerLeaseStore.Expired::incarnation)
                        .toList());
    }

    @Test
    void restartedProcessGetsANewIncarnationAndCannotResurrectTheOldOne() {
        var old = store.register("hands", "pod-1", ttl);
        store.retire(old);
        var current = store.register("hands", "pod-1", ttl);
        assertNotEquals(old.incarnation(), current.incarnation());
        assertFalse(store.renew(old, ttl));
        assertTrue(store.renew(current, ttl));
        assertThrows(
                ManagedStateException.class,
                () ->
                        store.whileValid(
                                new ManagedWorkerLeaseStore.Lease(
                                        current.incarnation(), "brain", "pod-1"),
                                () -> true));
    }

    @Test
    void metadataAndLeaseCheckShareTheSameRollbackBoundary() {
        var lease = store.register("brain", "pod-1", ttl);
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE fixture_commit(id INTEGER PRIMARY KEY)");
        assertThrows(
                IllegalStateException.class,
                () ->
                        store.whileValid(
                                lease,
                                () -> {
                                    jdbc.update("INSERT INTO fixture_commit VALUES(1)");
                                    throw new IllegalStateException("commit failed");
                                }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM fixture_commit", Integer.class));
        assertTrue(
                store.whileValid(
                        lease,
                        () -> {
                            jdbc.update("INSERT INTO fixture_commit VALUES(1)");
                            return true;
                        }));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM fixture_commit", Integer.class));
    }
}
