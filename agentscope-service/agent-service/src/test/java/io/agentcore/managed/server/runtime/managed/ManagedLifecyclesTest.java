/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ManagedLifecyclesTest {
    @TempDir private Path managedCheckpointRoot;

    private final ManagedScope scope = new ManagedScope("t", "n", "life-" + UUID.randomUUID());
    private ManagedAdmissionStore admission;
    private ManagedLifecycles lifecycles;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        DataSource source = database();
        ManagedAdmissionStore.initialize(source);
        admission = new ManagedAdmissionStore(source, new ObjectMapper());
        lifecycles = new ManagedLifecycles(source);
        jdbc = new JdbcTemplate(source);
        admission.createSession(scope, "{}");
    }

    protected DataSource database() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:life-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    @Test
    void concurrentAdmissionCannotCrossLifecycleClosure() throws Exception {
        String turn = admission.acceptTurn(scope, "actor", "original", "{}").id();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(9);
        var receipts = new ArrayList<Future<Boolean>>();
        try {
            for (int index = 0; index < 8; index++) {
                String key = "input-" + index;
                receipts.add(
                        executor.submit(
                                () -> {
                                    assertTrue(start.await(5, TimeUnit.SECONDS));
                                    try {
                                        admission.acceptInput(
                                                scope,
                                                turn,
                                                "actor",
                                                key,
                                                ManagedInputInbox.Type.INJECT,
                                                null,
                                                "{}");
                                        return true;
                                    } catch (ManagedStateException rejected) {
                                        assertEquals("session_inactive", rejected.code());
                                        return false;
                                    }
                                }));
            }
            var close =
                    executor.submit(
                            () -> {
                                assertTrue(start.await(5, TimeUnit.SECONDS));
                                return lifecycles.stage(scope, "archived", "actor", "archive");
                            });
            start.countDown();
            close.get(10, TimeUnit.SECONDS);
            long accepted = 0;
            for (var receipt : receipts) if (receipt.get(10, TimeUnit.SECONDS)) accepted++;
            assertEquals(accepted, admission.inputsAfter(scope, turn, 0, 16).size());
            assertEquals(accepted, admission.turn(scope, turn).inputSequence());
            assertFalse(lifecycles.active(scope));
            assertTrue(
                    ProjectionTestSupport.journal(
                                    jdbc.getDataSource(),
                                    new ObjectMapper(),
                                    new ManagedCheckpointObjects(
                                            new ObjectMapper(), managedCheckpointRoot))
                            .cancelRequested(scope, turn));
            assertThrows(
                    ManagedStateException.class,
                    () -> admission.acceptTurn(scope, "actor", "after", "{}"));
            assertThrows(
                    ManagedStateException.class,
                    () ->
                            admission.acceptInput(
                                    scope,
                                    turn,
                                    "actor",
                                    "after",
                                    ManagedInputInbox.Type.INJECT,
                                    null,
                                    "{}"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void closurePreservesTheInputCutoffAndRebuildsRedisCancellation() {
        var turn = admission.acceptTurn(scope, "actor", "task", "{}");
        admission.reservePublication(scope, turn.id());
        admission.confirmPublication(scope, turn.id(), "broker");
        long epoch = admission.activate(scope, turn.id(), 1, "token");
        assertEquals(0, admission.closeInputs(scope, turn.id(), epoch));
        var command = lifecycles.stage(scope, "archived", "actor", "archive");
        assertEquals(command, lifecycles.stage(scope, "archived", "actor", "archive"));
        assertEquals(
                "lifecycle_content_conflict",
                assertThrows(
                                ManagedStateException.class,
                                () -> lifecycles.stage(scope, "deleted", "actor", "archive"))
                        .code());
        assertEquals(
                "session_inactive",
                assertThrows(
                                ManagedStateException.class,
                                () -> admission.acceptTurn(scope, "actor", "new", "{}"))
                        .code());
        assertEquals(0, admission.turn(scope, turn.id()).inputSequence());
        assertTrue(admission.inputsAfter(scope, turn.id(), 0, 10).isEmpty());
        var first = new InMemoryManagedInputInbox(System::currentTimeMillis);
        new ManagedInputDelivery(admission, first).refill(scope, turn.id(), 0, 10);
        assertTrue(first.cancelled(scope, turn.id()));
        var restarted = new InMemoryManagedInputInbox(System::currentTimeMillis);
        new ManagedInputDelivery(admission, restarted).refill(scope, turn.id(), 0, 10);
        assertTrue(restarted.cancelled(scope, turn.id()));
        assertEquals(
                "lifecycle_pending",
                assertThrows(
                                ManagedStateException.class,
                                () -> lifecycles.stage(scope, "active", "actor", "restore"))
                        .code());
    }

    @Test
    void cleanupWaitsForBothTurnsAndToolsAndRestoreUsesANewGeneration() {
        String turn = admission.acceptTurn(scope, "actor", "task", "{}").id();
        var command = lifecycles.stage(scope, "archived", "actor", "archive");
        var effects = new AtomicInteger();
        try (var loop =
                loop(
                        new InMemoryManagedLeaseStore(System::currentTimeMillis),
                        (value, check) -> effects.incrementAndGet())) {
            assertFalse(loop.execute(command));
            assertEquals(0, effects.get());
            jdbc.update(
                    "UPDATE ma.turn SET status='cancelled' WHERE scope_key=?",
                    scope.messageGroup());
            jdbc.update(
                    "INSERT INTO"
                        + " ma.tool_task(scope_key,task_id,turn_id,tool_call_id,execution_id,tool_name,arguments,checkpoint_sequence,status)"
                        + " VALUES(?,?,?,?,?,'tool','{}',0,'unknown')",
                    scope.messageGroup(),
                    "task",
                    turn,
                    "call",
                    "execution");
            assertFalse(loop.execute(command));
            jdbc.update(
                    "UPDATE ma.tool_task SET status='cancelled' WHERE scope_key=?",
                    scope.messageGroup());
            assertTrue(loop.execute(command));
            assertEquals(1, effects.get());
            assertFalse(loop.execute(command));
        }
        var restored = lifecycles.stage(scope, "active", "actor", "restore");
        assertEquals("completed", restored.status());
        assertTrue(lifecycles.active(scope));
        assertTrue(restored.revision() > command.revision());
        assertNotEquals(turn, admission.acceptTurn(scope, "actor", "next", "{}").id());
    }

    @Test
    void lostCleanupOwnershipCannotConfirmCompletion() {
        var clock = new AtomicLong(1000);
        var leases = new InMemoryManagedLeaseStore(clock::get);
        var command = lifecycles.stage(scope, "deleted", "actor", "delete");
        try (var loop =
                loop(
                        leases,
                        (value, check) -> {
                            clock.addAndGet(61000);
                            check.run();
                        })) {
            assertThrows(ManagedTransportException.class, () -> loop.execute(command));
        }
        assertEquals("cleaning", lifecycles.get(scope, command.id()).status());
        try (var loop = loop(leases, (value, check) -> check.run())) {
            assertTrue(loop.execute(command));
        }
        assertEquals("completed", lifecycles.get(scope, command.id()).status());
        assertEquals(
                "session_not_found",
                assertThrows(
                                ManagedStateException.class,
                                () -> lifecycles.stage(scope, "active", "actor", "restore"))
                        .code());
    }

    private ManagedLifecycleLoop loop(
            ManagedLeaseStore leases, ManagedLifecycleLoop.Cleaner cleaner) {
        return new ManagedLifecycleLoop(
                lifecycles,
                leases,
                cleaner,
                1,
                Duration.ofSeconds(60),
                Duration.ofSeconds(15),
                (command, error) -> fail(error));
    }
}
