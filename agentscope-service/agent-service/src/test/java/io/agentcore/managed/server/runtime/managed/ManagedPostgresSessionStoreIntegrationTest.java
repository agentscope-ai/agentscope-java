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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** The same durable handoff, output and replay contracts against real PostgreSQL transactions. */
@EnabledIfEnvironmentVariable(named = "MANAGED_DATABASE_TEST_URI", matches = ".+")
class ManagedPostgresSessionStoreIntegrationTest extends ManagedSessionStoreTest {
    private ManagedSchemaMigrationIntegrationTest.TemporaryDatabase temporary;

    @Override
    protected DriverManagerDataSource database() throws Exception {
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
    void projectionClaimLocksTheEarliestVersionBeforeAnyLaterRow() throws Exception {
        for (int i = 0; i < 32; i++)
            journal.resourceEvent(
                    scope,
                    "ordered-" + i,
                    "session.updated",
                    Map.of("session_id", scope.sessionId()));
        // Put the first version after the others in heap order. An unordered UPDATE using a
        // sequential scan then acquires later rows first, reproducing the production lock
        // inversion.
        jdbc.update(
                "UPDATE ma.runtime_commit SET attempt_count=attempt_count WHERE scope_key=?"
                        + " AND public_commit_version=1",
                scope.messageGroup());
        var sequential =
                new DriverManagerDataSource(source.getUrl(), "managed", "managed-local-test");
        var properties = new Properties();
        properties.setProperty("options", "-c enable_indexscan=off -c enable_bitmapscan=off");
        sequential.setConnectionProperties(properties);
        var facts =
                new ManagedProjectionFacts(
                        sequential, mapper, new ManagedFactBundles(checkpoints.backend(), mapper));
        var outbox = new ManagedProjectionOutbox(sequential, mapper, facts);
        var pool = Executors.newSingleThreadExecutor();
        try (var first = source.getConnection();
                var later = source.getConnection()) {
            first.setAutoCommit(false);
            later.setAutoCommit(false);
            try (var lock =
                    first.prepareStatement(
                            "SELECT commit_id FROM ma.runtime_commit WHERE scope_key=?"
                                    + " AND public_commit_version=1 FOR UPDATE")) {
                lock.setString(1, scope.messageGroup());
                lock.executeQuery().close();
            }
            var claim = pool.submit(() -> outbox.claim(scope));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            boolean blocked = false;
            while (System.nanoTime() < deadline) {
                blocked =
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE"
                                    + " datname=current_database() AND wait_event_type='Lock' AND"
                                    + " query LIKE '%ma.runtime_commit%')",
                                Boolean.class);
                if (blocked) break;
                Thread.sleep(10);
            }
            assertTrue(blocked, "The publisher should be waiting on the first version");
            try (var lock =
                    later.prepareStatement(
                            "SELECT commit_id FROM ma.runtime_commit WHERE scope_key=?"
                                    + " AND public_commit_version=2 FOR UPDATE NOWAIT")) {
                lock.setString(1, scope.messageGroup());
                assertTrue(
                        lock.executeQuery().next(),
                        "No later row may be locked ahead of version 1");
            }
            later.commit();
            first.commit();
            var claimed = claim.get(3, TimeUnit.SECONDS);
            assertEquals(32, claimed.commits().size());
            outbox.completed(claimed);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void projectionContinuesWhileExecutionSessionAndAcceptedHeadAreLocked() throws Exception {
        journal.resourceEvent(
                scope, "visible", "session.created", Map.of("session_id", scope.sessionId()));
        var pool = Executors.newSingleThreadExecutor();
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (var lock =
                    connection.prepareStatement(
                            "SELECT scope_key FROM ma.session WHERE scope_key=? FOR UPDATE")) {
                lock.setString(1, scope.messageGroup());
                lock.executeQuery().close();
            }
            try (var lock =
                    connection.prepareStatement(
                            "SELECT scope_key FROM ma.runtime_commit WHERE scope_key=? FOR"
                                    + " UPDATE")) {
                lock.setString(1, scope.messageGroup());
                lock.executeQuery().close();
            }
            var projected = pool.submit(() -> journal.projections().project(scope, 32));
            assertEquals(1, projected.get(3, TimeUnit.SECONDS));
            assertEquals(1, journal.publicHead(scope));
            connection.rollback();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void privateCheckpointCommitDoesNotWaitForProjectionNotificationLock() throws Exception {
        var batch = batch("private-with-projection-locked", 0);
        var pool = Executors.newSingleThreadExecutor();
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (var lock =
                    connection.prepareStatement(
                            "SELECT scope_key FROM ma.runtime_commit WHERE scope_key=? FOR"
                                    + " UPDATE")) {
                lock.setString(1, scope.messageGroup());
                lock.executeQuery().close();
            }
            var committed =
                    pool.submit(
                            () ->
                                    AgentScopePreparation.commit(
                                            journal,
                                            new SessionCommitTransactions(journal),
                                            batch));
            assertEquals(1, committed.get(3, TimeUnit.SECONDS).sequence());
            connection.rollback();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, journal.head(scope).sequence());
        assertEquals(0, journal.projections().acceptedVersion(scope));
    }

    @Test
    void checkpointCommitRechecksTurnAfterWaitingForSessionLock() throws Exception {
        var batch = batch("blocked-stale-owner", 0);
        var pool = Executors.newSingleThreadExecutor();
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (var lock =
                    connection.prepareStatement(
                            "SELECT scope_key FROM ma.session WHERE scope_key=? FOR UPDATE")) {
                lock.setString(1, scope.messageGroup());
                lock.executeQuery().close();
            }
            try (var update =
                    connection.prepareStatement(
                            "UPDATE ma.turn SET execution_token='replacement' WHERE scope_key=?")) {
                update.setString(1, scope.messageGroup());
                update.executeUpdate();
            }
            var committed =
                    pool.submit(
                            () ->
                                    AgentScopePreparation.commit(
                                            journal,
                                            new SessionCommitTransactions(journal),
                                            batch));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            boolean blocked = false;
            while (System.nanoTime() < deadline) {
                blocked =
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE"
                                    + " datname=current_database() AND wait_event_type='Lock' AND"
                                    + " query LIKE '%FROM ma.session%')",
                                Boolean.class);
                if (blocked) break;
                Thread.sleep(10);
            }
            assertTrue(blocked, "Commit must wait behind the owner-changing transaction");
            connection.commit();
            var failure =
                    assertThrows(
                            ExecutionException.class, () -> committed.get(3, TimeUnit.SECONDS));
            assertEquals("stale_execution", ((ManagedStateException) failure.getCause()).code());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, journal.head(scope).sequence());
    }
}
