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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

class ManagedProcessOwnershipTest {
    protected DataSource database() throws Exception {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:process-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    @Test
    void durableOwnerFencesCompetitorAndExpiredOwnerCannotCommitAfterTakeover() throws Exception {
        var source = database();
        ManagedAdmissionStore.initialize(source);
        var mapper = new ObjectMapper();
        var jdbc = new JdbcTemplate(source);
        var scope = new ManagedScope("t", "n", "s");
        var first = new ManagedAdmissionStore(source, mapper);
        var second = new ManagedAdmissionStore(source, mapper);
        first.createSession(scope, "{}");
        first.queue().enable(scope);
        var turn = first.acceptTurn(scope, "a", "key", "{}");
        try (var a =
                        new ManagedProcessOwnership(
                                source,
                                "brain",
                                Duration.ofSeconds(60),
                                Duration.ofMinutes(10),
                                Duration.ofHours(1));
                var b =
                        new ManagedProcessOwnership(
                                source,
                                "brain",
                                Duration.ofSeconds(60),
                                Duration.ofMinutes(10),
                                Duration.ofHours(1))) {
            first.processOwnership(a);
            second.processOwnership(b);
            long epoch = first.activate(scope, turn.id(), turn.publicationSequence(), "one");
            assertEquals(
                    epoch, first.activate(scope, turn.id(), turn.publicationSequence(), "one"));
            assertEquals(
                    "execution_busy",
                    assertThrows(
                                    ManagedStateException.class,
                                    () ->
                                            second.activate(
                                                    scope,
                                                    turn.id(),
                                                    turn.publicationSequence(),
                                                    "two"))
                            .code());
            jdbc.update(
                    "UPDATE ma_worker.lease SET expires_at=0 WHERE incarnation=?", a.incarnation());
            assertThrows(
                    ManagedStateException.class,
                    () ->
                            a.transaction(
                                    () -> {
                                        a.requireTurn(scope, turn.id());
                                        return true;
                                    }));
            assertEquals(
                    epoch + 1,
                    second.activate(scope, turn.id(), turn.publicationSequence(), "two"));
            assertTrue(
                    b.transaction(
                            () -> {
                                b.requireTurn(scope, turn.id());
                                return true;
                            }));
            jdbc.update(
                    "UPDATE ma.turn SET execution_deadline=0 WHERE scope_key=?",
                    scope.messageGroup());
            assertThrows(
                    ManagedStateException.class,
                    () -> second.activate(scope, turn.id(), turn.publicationSequence(), "two"));
            assertEquals(
                    epoch + 2,
                    second.activate(scope, turn.id(), turn.publicationSequence(), "three"));
        }
    }

    @Test
    void toolWaitDoesNotRenewSqlPerTaskAndHealthyOwnerIsExcludedFromRepair() throws Exception {
        var source = database();
        ManagedAdmissionStore.initialize(source);
        var jdbc = new JdbcTemplate(source);
        var store = new ManagedToolWorkerStore(source, new ObjectMapper());
        var scope = new ManagedScope("t", "n", "s");
        var task =
                new ManagedJournalStore.ToolTask(
                        "task",
                        "turn",
                        "call",
                        "task",
                        "mcp.call",
                        "{}",
                        1,
                        "ready",
                        null,
                        null,
                        null);
        store.receive(new ManagedToolCommand(scope, "command", "execute", task, "{}"));
        try (var owner =
                new ManagedProcessOwnership(
                        source,
                        "hands",
                        Duration.ofSeconds(60),
                        Duration.ofMinutes(10),
                        Duration.ofHours(1))) {
            store.processOwnership(owner);
            store.claimTool(scope, task.id(), "token");
            long at =
                    jdbc.queryForObject(
                            "SELECT last_dispatched_at FROM ma.hands_execution", Long.class);
            for (int i = 0; i < 50; i++)
                store.toolHeartbeat(scope, task.id(), "token", at + 1000 + i);
            assertEquals(
                    at,
                    jdbc.queryForObject(
                            "SELECT last_dispatched_at FROM ma.hands_execution", Long.class));
            jdbc.update("UPDATE ma.hands_execution SET last_dispatched_at=0");
            assertTrue(store.pendingExecutions(10, System.currentTimeMillis(), 1, 1).isEmpty());
            jdbc.update(
                    "UPDATE ma_worker.lease SET expires_at=0 WHERE incarnation=?",
                    owner.incarnation());
            assertEquals(1, store.pendingExecutions(10, System.currentTimeMillis(), 1, 1).size());
            assertThrows(
                    ManagedStateException.class,
                    () -> store.recordToolHandle(scope, task.id(), "token", "handle"));
        }
    }

    @Test
    void expiredProcessRequestsRestartOnceAndCannotRevive() throws Exception {
        var source = database();
        ManagedAdmissionStore.initialize(source);
        try (var owner =
                new ManagedProcessOwnership(
                        source,
                        "brain",
                        Duration.ofSeconds(60),
                        Duration.ofMinutes(10),
                        Duration.ofHours(1))) {
            var restarts = new AtomicInteger();
            owner.onExpired(restarts::incrementAndGet);
            new JdbcTemplate(source)
                    .update(
                            "UPDATE ma_worker.lease SET expires_at=0 WHERE incarnation=?",
                            owner.incarnation());
            ReflectionTestUtils.invokeMethod(owner, "renew");
            assertThrows(ManagedStateException.class, owner::checkLocal);
            ReflectionTestUtils.invokeMethod(owner, "renew");
            assertEquals(1, restarts.get());
            assertThrows(ManagedStateException.class, () -> owner.transaction(() -> true));
        }
    }
}
