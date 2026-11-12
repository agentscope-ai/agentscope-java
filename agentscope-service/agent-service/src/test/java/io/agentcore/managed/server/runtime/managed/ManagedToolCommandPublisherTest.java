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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Publication contention/fault tests run unchanged on an isolated PostgreSQL database as well. */
class ManagedToolCommandPublisherTest extends ManagedToolSubmissionTest {
    @Test
    void failedBatchClaimRollsBackEveryLeaseBeforeSendingAndRemainsRecoverable() throws Exception {
        prepareCommands();
        for (String id : new String[] {"a", "b", "c"}) seed(id, 1, 1, true);
        jdbc.execute(
                "ALTER TABLE ma.tool_command_outbox ADD CONSTRAINT reject_middle_claim"
                        + " CHECK (command_id<>'b' OR publish_attempt=0)");
        assertThrows(DataAccessException.class, () -> publisher().publishCommands(3));
        assertTrue(deliveries.isEmpty());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM ma.tool_command_outbox"
                                + " WHERE publish_attempt<>0 OR publisher_token IS NOT NULL",
                        Integer.class));
        jdbc.execute("ALTER TABLE ma.tool_command_outbox DROP CONSTRAINT reject_middle_claim");
        assertEquals(3, publisher().publishCommands(3));
        assertEquals(
                List.of("a", "b", "c"),
                deliveries.stream().map(ManagedToolCommand::commandId).toList());
    }

    ManagedToolCommand template;

    void prepareCommands() throws Exception {
        commit(batch(1, "R1"));
        template =
                mapper.readValue(
                        jdbc.queryForObject(
                                "SELECT payload FROM ma.tool_command_outbox", String.class),
                        ManagedToolCommand.class);
        jdbc.update("DELETE FROM ma.tool_command_outbox");
    }

    void seed(String id, long created, long due, boolean authorized) throws Exception {
        var command =
                new ManagedToolCommand(
                        template.scope(),
                        id,
                        template.kind(),
                        template.task(),
                        template.configuration(),
                        template.submission());
        jdbc.update(
                "INSERT INTO"
                    + " ma.tool_command_outbox(command_id,scope_key,task_id,kind,payload,created_at,next_attempt_at,authorized)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                id,
                scope.messageGroup(),
                command.task().id(),
                "execute",
                mapper.writeValueAsString(command),
                created,
                due,
                authorized);
    }

    ManagedToolCommandPublisher asyncPublisher(int capacity, Duration repair) {
        var publisher =
                new ManagedToolCommandPublisher(source, bus, codec, mapper, capacity, repair);
        journal.transport().onPublicationReady(publisher::wakeup);
        publisher.start();
        return publisher;
    }

    static void await(BooleanSupplier ready) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(ready.getAsBoolean(), "condition did not become true");
    }

    @Test
    void outerTransactionSignalsOnlyAfterCommitAndNeverAfterRollback() throws Exception {
        var hints = new AtomicInteger();
        journal.transport().onPublicationReady(hints::incrementAndGet);
        var outer = new TransactionTemplate(new DataSourceTransactionManager(source));
        var boundary = batch(1, "R1");
        outer.executeWithoutResult(
                status -> {
                    commit(boundary);
                    assertEquals(0, hints.get());
                    status.setRollbackOnly();
                });
        assertEquals(0, hints.get());
        assertEquals(0, publisher().publishCommands(10));
        commit(batch(1, "R1"));
        assertEquals(1, hints.get());
    }

    @Test
    void commitReturnsWhileDedicatedPublisherIsBlockedInMq() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        reset(bus);
        when(bus.publish(any(), any()))
                .thenAnswer(
                        call -> {
                            entered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return new ManagedMessageBus.Published("sent");
                        });
        try (var publisher = asyncPublisher(2, Duration.ofHours(1))) {
            var boundary = batch(1, "R1");
            var committed = pool.submit(() -> commit(boundary));
            committed.get(5, TimeUnit.SECONDS);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("committed", journal.receipt(scope, boundary.batchId()).outcome());
            release.countDown();
            await(
                    () ->
                            jdbc.queryForObject(
                                            "SELECT count(*) FROM ma.tool_command_outbox WHERE"
                                                    + " broker_id IS NOT NULL",
                                            Integer.class)
                                    == 1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void failedCommitHintDoesNotUndoDurableAuthorization() throws Exception {
        journal.transport()
                .onPublicationReady(
                        () -> {
                            throw new IllegalStateException("shutdown");
                        });
        commit(batch(1, "R1"));
        assertEquals(1, publisher().publishCommands(1));
    }

    @Test
    void oldestEligibleCommandWinsRatherThanLexicalIdAndBackoffDoesNotBlockOthers()
            throws Exception {
        prepareCommands();
        seed("z-old", 1, 1, true);
        seed("a-new", 2, 1, true); // Equal eligibility still prefers the older creation time.
        seed("0-retry", 0, System.currentTimeMillis() + 60000, true);
        seed("0-unauthorized", 0, 0, false);
        assertEquals(1, publisher().publishCommands(1));
        assertEquals("z-old", deliveries.get(0).commandId());
        assertEquals(1, publisher().publishCommands(1));
        assertEquals("a-new", deliveries.get(1).commandId());
        assertEquals(0, publisher().publishCommands(10));
    }

    @Test
    void saturationDoesNotClaimWaitingCommandsAndCompletionImmediatelyDrainsBacklog()
            throws Exception {
        prepareCommands();
        for (int i = 0; i < 8; i++) seed("c-" + i, i, i, true);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        reset(bus);
        when(bus.publish(any(), any()))
                .thenAnswer(
                        call -> {
                            int n = active.incrementAndGet();
                            peak.accumulateAndGet(n, Math::max);
                            entered.countDown();
                            try {
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                                return new ManagedMessageBus.Published("sent");
                            } finally {
                                active.decrementAndGet();
                            }
                        });
        try (var publisher = asyncPublisher(2, Duration.ofHours(1))) {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 1000; i++) publisher.wakeup();
            assertEquals(
                    2,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM ma.tool_command_outbox WHERE publish_attempt>0",
                            Integer.class));
            assertEquals(0, publisher.publishCommands(8));
            release.countDown();
            await(
                    () ->
                            jdbc.queryForObject(
                                            "SELECT count(*) FROM ma.tool_command_outbox WHERE"
                                                    + " broker_id IS NOT NULL",
                                            Integer.class)
                                    == 8);
            assertEquals(2, peak.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void lockedOldestRowDoesNotBlockAnotherInstanceAndIsNotLost() throws Exception {
        prepareCommands();
        seed("old", 1, 1, true);
        seed("new", 2, 2, true);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var hold =
                pool.submit(
                        () ->
                                new TransactionTemplate(new DataSourceTransactionManager(source))
                                        .execute(
                                                status -> {
                                                    jdbc.queryForObject(
                                                            "SELECT command_id FROM"
                                                                + " ma.tool_command_outbox WHERE"
                                                                + " command_id='old' FOR UPDATE",
                                                            String.class);
                                                    locked.countDown();
                                                    try {
                                                        assertTrue(
                                                                release.await(5, TimeUnit.SECONDS));
                                                    } catch (InterruptedException e) {
                                                        throw new RuntimeException(e);
                                                    }
                                                    return null;
                                                }));
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            assertEquals(1, publisher().publishCommands(1));
            assertEquals("new", deliveries.get(0).commandId());
        } finally {
            release.countDown();
        }
        hold.get(5, TimeUnit.SECONDS);
        assertEquals(1, publisher().publishCommands(1));
        assertEquals("old", deliveries.get(1).commandId());
    }

    @Test
    void expiredOwnerCannotOverwriteNewPublisherReceipt() throws Exception {
        prepareCommands();
        seed("same", 1, 1, true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        reset(bus);
        when(bus.publish(any(), any()))
                .thenAnswer(
                        call -> {
                            if (calls.incrementAndGet() == 1) {
                                entered.countDown();
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                                return new ManagedMessageBus.Published("old");
                            }
                            return new ManagedMessageBus.Published("new");
                        });
        var first = pool.submit(() -> publisher().publishCommands(1));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(0, publisher().publishCommands(1)); // Live owner is protected.
            jdbc.update("UPDATE ma.tool_command_outbox SET publisher_lease_until=0");
            assertEquals(1, publisher().publishCommands(1));
        } finally {
            release.countDown();
        }
        assertEquals(0, first.get(5, TimeUnit.SECONDS));
        assertEquals(
                "new",
                jdbc.queryForObject("SELECT broker_id FROM ma.tool_command_outbox", String.class));
    }

    @Test
    void lostBrokerResponseRepublishesSameIdentityAndHandsDeduplicates() throws Exception {
        commit(batch(1, "R1"));
        var calls = new AtomicInteger();
        reset(bus);
        when(bus.publish(any(), any()))
                .thenAnswer(
                        call -> {
                            deliveries.add(codec.decodeToolCommand(call.getArgument(1)));
                            if (calls.incrementAndGet() == 1)
                                throw new ManagedTransportException(
                                        "response lost after acceptance");
                            return new ManagedMessageBus.Published("retry");
                        });
        assertEquals(0, publisher().publishCommands(1));
        assertEquals(0, publisher().publishCommands(1)); // Honors retry backoff.
        jdbc.update("UPDATE ma.tool_command_outbox SET next_attempt_at=0");
        assertEquals(1, publisher().publishCommands(1));
        assertEquals(deliveries.get(0), deliveries.get(1));
        for (var command : deliveries) commands.consume(delivery(command));
        assertTrue(worker.claimTool(scope, deliveries.get(0).task().id(), "first").firstLaunch());
        assertFalse(
                worker.claimTool(scope, deliveries.get(0).task().id(), "duplicate").firstLaunch());
    }

    @Test
    void brokerAcceptedButReceiptWriteFailedRetriesWithoutAnotherBoundaryCommit() throws Exception {
        commit(batch(1, "R1"));
        jdbc.execute(
                "ALTER TABLE ma.tool_command_outbox ADD CONSTRAINT reject_receipt"
                        + " CHECK (broker_id IS NULL)");
        assertEquals(0, publisher().publishCommands(1));
        assertEquals(1, deliveries.size());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM ma.tool_command_outbox WHERE broker_id IS NULL",
                        Integer.class));
        jdbc.execute("ALTER TABLE ma.tool_command_outbox DROP CONSTRAINT reject_receipt");
        jdbc.update("UPDATE ma.tool_command_outbox SET next_attempt_at=0");
        assertEquals(1, publisher().publishCommands(1));
        assertEquals(deliveries.get(0), deliveries.get(1));
        assertEquals(
                1, jdbc.queryForObject("SELECT count(*) FROM ma.runtime_commit", Integer.class));
        for (var command : deliveries) commands.consume(delivery(command));
        assertTrue(worker.claimTool(scope, deliveries.get(0).task().id(), "first").firstLaunch());
        assertFalse(
                worker.claimTool(scope, deliveries.get(0).task().id(), "duplicate").firstLaunch());
    }

    @Test
    void startupAndLowFrequencyRepairRecoverMissingHintsAndExpiredClaims() throws Exception {
        prepareCommands();
        seed("crashed", 1, 1, true);
        jdbc.update(
                "UPDATE ma.tool_command_outbox SET publisher_token='dead',publisher_lease_until=?",
                System.currentTimeMillis() + 300);
        try (var publisher = asyncPublisher(1, Duration.ofMillis(100))) {
            await(
                    () ->
                            jdbc.queryForObject(
                                            "SELECT count(*) FROM ma.tool_command_outbox WHERE"
                                                    + " broker_id IS NOT NULL",
                                            Integer.class)
                                    == 1);
            assertEquals("crashed", deliveries.get(0).commandId());
        }
    }

    @Test
    void blockedProjectionNotificationDoesNotDelayPostCommitToolPublication() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var tools = new CountDownLatch(1);
        reset(bus);
        when(bus.publish(any(), any()))
                .thenAnswer(
                        call -> {
                            if (call.getArgument(0) == Channel.SESSION_PROJECTIONS) {
                                entered.countDown();
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            } else tools.countDown();
                            return new ManagedMessageBus.Published("sent");
                        });
        journal.resourceEvent(
                scope, "created", "session.created", Map.of("session_id", scope.sessionId()));
        var projection =
                pool.submit(() -> new ManagedProjectionPublisher(journal, bus, mapper).scan());
        try (var publisher = asyncPublisher(1, Duration.ofHours(1))) {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            commit(batch(1, "R1"));
            assertTrue(tools.await(5, TimeUnit.SECONDS));
            await(
                    () ->
                            jdbc.queryForObject(
                                            "SELECT count(*) FROM ma.tool_command_outbox WHERE"
                                                    + " broker_id IS NOT NULL",
                                            Integer.class)
                                    == 1);
        } finally {
            release.countDown();
        }
        projection.get(5, TimeUnit.SECONDS);
    }

    @Test
    void twoInstancesPublishDisjointClaimsWithoutDuplicateActiveOwnership() throws Exception {
        prepareCommands();
        for (int i = 0; i < 12; i++) seed("task-" + i, i, i, true);
        var delivered = new ConcurrentHashMap<String, AtomicInteger>();
        var bothInstances = new CountDownLatch(4); // Each instance can hold only two claims.
        var firstClaimed = new CountDownLatch(2);
        reset(bus);
        when(bus.publish(any(), any()))
                .thenAnswer(
                        call -> {
                            var command = codec.decodeToolCommand(call.getArgument(1));
                            firstClaimed.countDown();
                            bothInstances.countDown();
                            assertTrue(bothInstances.await(5, TimeUnit.SECONDS));
                            delivered
                                    .computeIfAbsent(
                                            command.commandId(), ignored -> new AtomicInteger())
                                    .incrementAndGet();
                            return new ManagedMessageBus.Published(command.commandId());
                        });
        try (var first = asyncPublisher(2, Duration.ofHours(1))) {
            // Establish committed claims before starting the second instance. A simultaneous
            // SKIP LOCKED scan may legitimately see no candidate while the first scan holds locks.
            assertTrue(firstClaimed.await(5, TimeUnit.SECONDS));
            try (var second = asyncPublisher(2, Duration.ofHours(1))) {
                await(
                        () ->
                                jdbc.queryForObject(
                                                "SELECT count(*) FROM ma.tool_command_outbox WHERE"
                                                        + " broker_id IS NOT NULL",
                                                Integer.class)
                                        == 12);
                assertEquals(12, delivered.size());
                assertTrue(delivered.values().stream().allMatch(count -> count.get() == 1));
            }
        }
    }

    @Test
    void projectionNotificationsHaveTheirOwnBoundedCapacity() throws Exception {
        for (int i = 0; i < 4; i++) {
            var other = new ManagedScope("tenant", "ns", "projection-" + i);
            admission.createSession(other, "{}");
            journal.resourceEvent(
                    other, "created", "session.created", Map.of("session_id", other.sessionId()));
        }
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var finished = new AtomicInteger();
        reset(bus);
        when(bus.publish(any(), any()))
                .thenAnswer(
                        call -> {
                            assertEquals(Channel.SESSION_PROJECTIONS, call.getArgument(0));
                            int count = active.incrementAndGet();
                            peak.accumulateAndGet(count, Math::max);
                            entered.countDown();
                            try {
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                                finished.incrementAndGet();
                                return new ManagedMessageBus.Published("notice");
                            } finally {
                                active.decrementAndGet();
                            }
                        });
        try (var publisher = new ManagedProjectionPublisher(journal, bus, mapper, 2)) {
            publisher.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(2, active.get());
            release.countDown();
            await(() -> finished.get() >= 4);
            assertEquals(2, peak.get());
        } finally {
            release.countDown();
        }
    }
}
