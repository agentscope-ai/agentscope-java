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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@Timeout(20)
class ManagedSessionQueueTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ManagedScope scope =
            new ManagedScope("tenant", "namespace", UUID.randomUUID().toString());
    private ManagedAdmissionStore admission;
    private ManagedSessionQueue queue;
    private ManagedSessionProtocol protocol;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;

    protected DriverManagerDataSource fixtureSource() throws Exception {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:queue-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    @BeforeEach
    void setup() throws Exception {
        var source = fixtureSource();
        ManagedAdmissionStore.initialize(source);
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        admission = new ManagedAdmissionStore(source, mapper);
        admission.createSession(scope, "{}");
        queue = admission.queue();
        queue.enable(scope);
        protocol = admission.protocol();
    }

    private String submit(String key) {
        var accepted =
                protocol.accept(
                        scope,
                        "actor",
                        key,
                        List.of(
                                new ManagedSessionProtocol.Command(
                                        "user.message",
                                        List.of(Map.of("type", "text", "text", key)),
                                        "new_turn",
                                        null,
                                        true)),
                        () -> {});
        return (String) extension(accepted).get("turn_id");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extension(ManagedSessionProtocol.Acceptance accepted) {
        return (Map<String, Object>)
                ((Map<?, ?>) ((List<?>) accepted.receipt().get("data")).get(0))
                        .get("managed_agents");
    }

    private ManagedSessionProtocol.Acceptance mutate(
            String key, String turn, long version, String target, boolean convert) {
        return protocol.acceptQueue(
                scope,
                "actor",
                key,
                new ManagedSessionQueue.Mutation("queue_" + turn, version, target, convert),
                () -> {});
    }

    @Test
    void withdrawalIsDurableAndSkipsSequenceWithoutForgingBrokerReceipts() {
        String first = submit("first"), second = submit("second");
        assertEquals(first, queue.candidate(scope));
        mutate("withdraw", first, 1, null, false);
        assertEquals("cancelled", admission.turn(scope, first).status());
        assertEquals("withdrawn", queue.entry(scope, "queue_" + first).get("state"));
        assertEquals(second, queue.candidate(scope));
        assertTrue(
                admission.pendingPublications(256).stream()
                        .noneMatch(item -> item.scope().equals(scope)));
        assertEquals("db_queued", admission.turn(scope, second).deliveryStatus());
        assertEquals(1, admission.activate(scope, second, 2, "token"));
        assertEquals("claimed", queue.entry(scope, "queue_" + second).get("state"));
        assertEquals(0, admission.session(scope).publishedSequence());
        assertThrows(
                ManagedStateException.class, () -> admission.reservePublication(scope, second));
        assertThrows(ManagedStateException.class, () -> mutate("late", second, 2, null, false));
        assertEquals(false, protocol.turnInfo(scope, first).get("execution_started"));
    }

    @Test
    void conversionPreservesSubmissionAndDoesNotCloseActiveTurnOrMarkSessionIdle() {
        String active = submit("active"), pending = submit("steering");
        admission.activate(scope, active, 1, "token");
        tx.executeWithoutResult(
                ignored ->
                        ManagedSessionProtocol.project(
                                jdbc,
                                mapper,
                                scope,
                                "running",
                                "turn.running",
                                Map.of("turn_id", active),
                                System.currentTimeMillis()));
        String submission = (String) protocol.link(scope, pending, null).get("event_id");
        var result = mutate("convert", pending, 1, active, true);
        String input = (String) extension(result).get("input_id");
        assertEquals(
                admission.turn(scope, pending).input(), admission.input(scope, input).payload());
        assertEquals(admission.turn(scope, pending).actor(), admission.input(scope, input).actor());
        assertEquals(active, admission.session(scope).currentTurnId());
        assertEquals(
                "running",
                jdbc.queryForObject(
                        "SELECT status FROM ma.protocol_session WHERE scope_key=?",
                        String.class,
                        scope.messageGroup()));
        assertEquals(submission, protocol.link(scope, pending, null).get("event_id"));
        assertNull(protocol.link(scope, active, input).get("applied_at"));
        assertEquals("converted", queue.entry(scope, "queue_" + pending).get("state"));
        assertEquals("accepted", admission.input(scope, input).status());
        assertEquals(
                "applied",
                queue.command(scope, (String) extension(result).get("command_id")).get("status"));
        tx.executeWithoutResult(
                ignored ->
                        ManagedSessionProtocol.project(
                                jdbc,
                                mapper,
                                scope,
                                "applied",
                                "input.applied",
                                Map.of("input_id", input, "turn_id", active),
                                System.currentTimeMillis()));
        assertNotNull(protocol.link(scope, pending, null).get("applied_at"));
        assertEquals(submission, protocol.link(scope, active, input).get("event_id"));
        assertFalse((Boolean) protocol.output(scope, pending, null, 10).get("output_available"));
    }

    @Test
    void frozenIdempotentReceiptWinsOverChangedVersionAndClosedTarget() {
        String active = submit("active"), pending = submit("pending");
        admission.activate(scope, active, 1, "token");
        var accepted = mutate("convert", pending, 1, active, true);
        admission.closeInputs(scope, active, 1);
        var replay = mutate("convert", pending, 1, active, true);
        assertTrue(replay.replayed());
        assertEquals(accepted.receipt(), replay.receipt());
        assertEquals(
                "idempotency_conflict",
                assertThrows(
                                ManagedStateException.class,
                                () -> mutate("convert", pending, 2, active, true))
                        .code());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.input WHERE scope_key=?",
                        Integer.class,
                        scope.messageGroup()));
    }

    @Test
    void closedOrNonCurrentTargetAndStaleVersionLeaveOriginalEntryQueued() {
        String active = submit("active"), pending = submit("pending");
        assertEquals(
                "target_not_active",
                assertThrows(
                                ManagedStateException.class,
                                () -> mutate("no-active", pending, 1, active, true))
                        .code());
        admission.activate(scope, active, 1, "token");
        assertEquals(
                "queue_version_conflict",
                assertThrows(
                                ManagedStateException.class,
                                () -> mutate("stale", pending, 2, active, true))
                        .code());
        admission.closeInputs(scope, active, 1);
        assertEquals(
                "input_closed",
                assertThrows(
                                ManagedStateException.class,
                                () -> mutate("closed", pending, 1, active, true))
                        .code());
        assertEquals("queued", queue.entry(scope, "queue_" + pending).get("state"));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.input WHERE scope_key=?",
                        Integer.class,
                        scope.messageGroup()));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.session_command WHERE scope_key=?",
                        Integer.class,
                        scope.messageGroup()));
    }

    @Test
    void claimAndWithdrawalHaveExactlyOneWinner() throws Exception {
        for (int iteration = 0; iteration < 12; iteration++) {
            String turn = submit("race-" + iteration);
            var pool = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            try {
                var claim =
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        return admission.activate(
                                                        scope,
                                                        turn,
                                                        admission
                                                                .turn(scope, turn)
                                                                .publicationSequence(),
                                                        "token-" + turn)
                                                > 0;
                                    } catch (ManagedStateException conflict) {
                                        return false;
                                    }
                                });
                var withdraw =
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        mutate("withdraw-" + turn, turn, 1, null, false);
                                        return true;
                                    } catch (ManagedStateException conflict) {
                                        return false;
                                    }
                                });
                start.countDown();
                boolean activated = claim.get(5, TimeUnit.SECONDS),
                        withdrawn = withdraw.get(5, TimeUnit.SECONDS);
                assertTrue(withdrawn ^ activated);
                // Activation of a terminal cancelled Turn is an idempotent no-op, not a claim.
                if (withdrawn)
                    assertEquals("withdrawn", queue.entry(scope, "queue_" + turn).get("state"));
                else assertEquals("claimed", queue.entry(scope, "queue_" + turn).get("state"));
                tx.executeWithoutResult(
                        ignored -> {
                            queue.lock(scope);
                            ManagedSessionQueue.consumed(jdbc, scope, turn);
                            jdbc.update(
                                    "UPDATE ma.session SET current_turn_id=NULL WHERE scope_key=?",
                                    scope.messageGroup());
                            jdbc.update(
                                    "UPDATE ma.turn SET status='succeeded' WHERE scope_key=? AND"
                                            + " turn_id=? AND status<>'cancelled'",
                                    scope.messageGroup(),
                                    turn);
                        });
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void newerGenerationSurvivesWorkerReleaseAndExpiredClaimRecovers() {
        submit("first");
        var claimed = queue.acquire(scope);
        assertNotNull(claimed);
        assertNull(queue.acquire(scope));
        submit("concurrent");
        queue.release(claimed);
        assertTrue(queue.due(10000).contains(scope));
        var newer = queue.acquire(scope);
        assertTrue(newer.generation() > claimed.generation());
        jdbc.update(
                "UPDATE ma.session_dispatch SET claim_until=0 WHERE scope_key=?",
                scope.messageGroup());
        var replacement = queue.acquire(scope);
        assertNotNull(replacement);
        queue.release(newer); // stale worker cannot clear replacement's claim
        assertNull(queue.acquire(scope));
        queue.release(replacement);
    }

    @Test
    void notificationAckStillLeavesRecoverableWorkAndIdleQueueParksUntilNewSubmission() {
        String turn = submit("first");
        var ready = queue.notification(scope);
        queue.notified(ready);
        queue.observe(ready);
        assertNull(queue.notification(scope));
        assertTrue(queue.due(10000).contains(scope));
        mutate("withdraw", turn, 1, null, false);
        var claim = queue.acquire(scope);
        queue.release(claim);
        assertFalse(queue.due(10000).contains(scope));
        String next = submit("next");
        assertTrue(queue.due(10000).contains(scope));
        assertEquals(next, queue.candidate(scope));
        assertThrows(
                ManagedStateException.class,
                () ->
                        queue.observe(
                                new ManagedSessionQueue.Ready(
                                        1, scope, ManagedSessionQueue.MODE, 2, 1, "stale")));
    }

    @Test
    void queryPaginationScopeAndInputResolutionAreExplicit() {
        String first = submit("one"), second = submit("two");
        var page = queue.page(scope, false, null, 1);
        assertEquals(true, page.get("has_more"));
        assertEquals("queue_" + first, page.get("next_cursor"));
        assertEquals(false, queue.page(scope, false, "queue_" + first, 1).get("has_more"));
        var other = new ManagedScope("tenant", "namespace", UUID.randomUUID().toString());
        admission.createSession(other, "{}");
        queue.enable(other);
        assertThrows(ManagedStateException.class, () -> queue.entry(other, "queue_" + second));
        assertThrows(
                ManagedStateException.class, () -> queue.page(other, false, "queue_" + first, 10));
        admission.activate(scope, first, 1, "token");
        var accepted =
                protocol.accept(
                        scope,
                        "actor",
                        "steer",
                        List.of(
                                new ManagedSessionProtocol.Command(
                                        "user.message",
                                        List.of(Map.of("type", "text", "text", "steer")),
                                        "steer",
                                        first,
                                        true)),
                        () -> {});
        String input = (String) extension(accepted).get("input_id"),
                command = (String) extension(accepted).get("event_id");
        jdbc.update(
                "UPDATE ma.input SET status='rejected',rejection_reason='target_closed' WHERE"
                        + " input_id=?",
                input);
        var receipt = queue.command(scope, command);
        assertEquals("rejected", receipt.get("status"));
        assertEquals("target_closed", receipt.get("resolution_reason"));
    }

    @Test
    void lifecycleWithdrawsUnstartedQueueWithoutLosingCurrentOwner() {
        String active = submit("active"), pending = submit("pending");
        admission.activate(scope, active, 1, "token");
        var lifecycle = new ManagedLifecycles(jdbc.getDataSource());
        lifecycle.stage(scope, "archived", "actor", "archive");
        assertEquals("withdrawn", queue.entry(scope, "queue_" + pending).get("state"));
        assertEquals("cancelled", admission.turn(scope, pending).status());
        assertEquals(active, admission.session(scope).currentTurnId());
        assertEquals(active, queue.candidate(scope));
        assertFalse(lifecycle.quiescent(scope));
    }

    @Test
    void readyWireEnvelopeIsVersionedAndRoundTrips() throws Exception {
        submit("ready");
        var ready = queue.notification(scope);
        var wire = mapper.readTree(mapper.writeValueAsBytes(ready));
        assertEquals(1, wire.path("schema_version").asInt());
        assertEquals("db_queue_v1", wire.path("scheduler_mode").asText());
        assertEquals(ready, mapper.treeToValue(wire, ManagedSessionQueue.Ready.class));
    }

    @Test
    void outOfOrderPublicationReceiptsDoNotLoseOrReopenPendingNotifications() {
        submit("first");
        var old = queue.notification(scope);
        submit("second");
        var fresh = queue.notification(scope);
        queue.notified(old);
        assertNotNull(queue.notification(scope));
        assertEquals(
                true,
                jdbc.queryForObject(
                        "SELECT notification_pending FROM ma.session_dispatch WHERE scope_key=?",
                        Boolean.class,
                        scope.messageGroup()));
        queue.notified(fresh);
        queue.notified(old);
        assertNull(queue.notification(scope));
        assertEquals(
                false,
                jdbc.queryForObject(
                        "SELECT notification_pending FROM ma.session_dispatch WHERE scope_key=?",
                        Boolean.class,
                        scope.messageGroup()));
    }

    @Test
    void brokerConfirmedButUnclaimedReadyIsRenotifiedWithoutRunningTheTurn() {
        String turn = submit("stranded");
        var old = queue.notification(scope);
        queue.notified(old);
        long now = System.currentTimeMillis();
        assertEquals(0, queue.renotifyUnclaimed(now, Duration.ofMinutes(1), 256));
        ageNotification();
        assertEquals(1, queue.renotifyUnclaimed(now, Duration.ofMinutes(1), 256));
        var repaired = queue.notification(scope);
        assertEquals(old.requestedGeneration() + 1, repaired.requestedGeneration());
        assertEquals(turn, queue.candidate(scope));
        assertEquals("accepted", admission.turn(scope, turn).status());
        queue.notified(old); // Delayed original publication receipt cannot erase the new hint.
        assertNotNull(queue.notification(scope));
        queue.notified(repaired);
        assertEquals(0, queue.renotifyUnclaimed(now, Duration.ofMinutes(1), 256));
        assertEquals(0, admission.turn(scope, turn).executionEpoch());
    }

    @Test
    void slowPublicationGetsAFullDeliveryGracePeriodAfterBrokerConfirmation() {
        submit("slow-send");
        var ready = queue.notification(scope);
        ageNotification();
        queue.notified(ready);
        assertEquals(
                0, queue.renotifyUnclaimed(System.currentTimeMillis(), Duration.ofMinutes(1), 256));
    }

    @Test
    void readyRepairDoesNotNotifyActiveExecutionsOrWithdrawnWork() {
        String turn = submit("owned");
        queue.notified(queue.notification(scope));
        admission.activate(scope, turn, admission.turn(scope, turn).publicationSequence(), "owner");
        ageNotification();
        assertEquals(
                0, queue.renotifyUnclaimed(System.currentTimeMillis(), Duration.ofMinutes(1), 256));
        jdbc.update(
                "UPDATE ma.turn SET status='waiting_tool',resume_id='resume' WHERE scope_key=?",
                scope.messageGroup());
        assertEquals(
                0, queue.renotifyUnclaimed(System.currentTimeMillis(), Duration.ofMinutes(1), 256));
    }

    @Test
    void readyRepairSkipsWithdrawnQueueAndPendingOrDeferredNotifications() {
        String turn = submit("withdrawn");
        mutate("withdraw", turn, 1, null, false);
        queue.notified(queue.notification(scope));
        ageNotification();
        assertEquals(
                0, queue.renotifyUnclaimed(System.currentTimeMillis(), Duration.ofMinutes(1), 256));
        submit("pending");
        ageNotification();
        assertEquals(
                0, queue.renotifyUnclaimed(System.currentTimeMillis(), Duration.ofMinutes(1), 256));
        var ready = queue.notification(scope);
        queue.notified(ready);
        queue.defer(ready, Duration.ofMinutes(2));
        assertEquals(
                0, queue.renotifyUnclaimed(System.currentTimeMillis(), Duration.ofMinutes(1), 256));
    }

    @Test
    void concurrentReadyRepairCreatesOnlyOneNewGeneration() throws Exception {
        submit("stranded");
        var old = queue.notification(scope);
        queue.notified(old);
        ageNotification();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return queue.renotifyUnclaimed(
                                        System.currentTimeMillis(), Duration.ofMinutes(1), 256);
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return queue.renotifyUnclaimed(
                                        System.currentTimeMillis(), Duration.ofMinutes(1), 256);
                            });
            start.countDown();
            assertEquals(1, first.get() + second.get());
            assertEquals(
                    old.requestedGeneration() + 1, queue.notification(scope).requestedGeneration());
        } finally {
            pool.shutdownNow();
        }
    }

    private void ageNotification() {
        jdbc.update(
                "UPDATE ma.session_dispatch SET next_check_at=1 WHERE scope_key=?",
                scope.messageGroup());
    }

    @Test
    void readyRepairIsBatchBounded() {
        for (int i = 0; i < 3; i++) {
            var another = new ManagedScope("t", "n", UUID.randomUUID().toString());
            admission.createSession(another, "{}");
            queue.enable(another);
            admission.acceptTurn(another, "u", "turn", "{}");
            queue.notified(queue.notification(another));
            jdbc.update(
                    "UPDATE ma.session_dispatch SET next_check_at=1 WHERE scope_key=?",
                    another.messageGroup());
        }
        long now = System.currentTimeMillis();
        assertEquals(2, queue.renotifyUnclaimed(now, Duration.ofMinutes(1), 2));
        assertEquals(1, queue.renotifyUnclaimed(now, Duration.ofMinutes(1), 2));
        assertEquals(0, queue.renotifyUnclaimed(now, Duration.ofMinutes(1), 2));
    }

    @Test
    void idleNotificationsDoNotStarveAnAbandonedInitialTurn() {
        String idle = submit("idle");
        mutate("withdraw-idle", idle, 1, null, false);
        var active = new ManagedScope("t", "n", UUID.randomUUID().toString());
        admission.createSession(active, "{}");
        queue.enable(active);
        var turn = admission.acceptTurn(active, "actor", "active", "{}");
        assertEquals(active, queue.notifications(1).get(0).scope());
        var ready = queue.notification(active);
        queue.notified(ready);
        long epoch =
                admission.activate(
                        active,
                        turn.id(),
                        admission.turn(active, turn.id()).publicationSequence(),
                        "owner");
        assertTrue(queue.notifications(100).stream().noneMatch(r -> r.scope().equals(active)));
        assertTrue(admission.abandonExecution(active, turn.id(), epoch, "owner"));
        assertEquals(active, queue.notifications(1).get(0).scope());
        // Filtering is derived, not a destructive ACK of a still-needed generation.
        assertNotNull(queue.notification(scope));
    }

    @Test
    void queuedSuccessorOnlyBecomesPublishableAfterCurrentTurnReleasesSession() {
        String turn = submit("first");
        admission.activate(scope, turn, admission.turn(scope, turn).publicationSequence(), "owner");
        submit("second");
        assertTrue(queue.notifications(100).stream().noneMatch(r -> r.scope().equals(scope)));
        jdbc.update(
                "UPDATE ma.turn SET status='waiting_tool' WHERE scope_key=? AND turn_id=?",
                scope.messageGroup(),
                turn);
        assertTrue(queue.notifications(100).stream().noneMatch(r -> r.scope().equals(scope)));
        tx.executeWithoutResult(
                ignored -> {
                    ManagedSessionQueue.consumed(jdbc, scope, turn);
                    jdbc.update(
                            "UPDATE ma.session SET current_turn_id=NULL WHERE scope_key=?",
                            scope.messageGroup());
                    ManagedSessionQueue.signal(jdbc, scope);
                });
        assertEquals(scope, queue.notifications(1).get(0).scope());
    }

    @Test
    void legacySessionsRetainBrokerAdmissionAndCannotBeSwitchedAfterFirstTurn() {
        var legacy = new ManagedScope("t", "n", UUID.randomUUID().toString());
        admission.createSession(legacy, "{}");
        assertFalse(queue.enabled(legacy));
        var turn = admission.acceptTurn(legacy, "actor", "one", "{}");
        assertNull(turn.publicationSequence());
        assertEquals("pending", admission.turn(legacy, turn.id()).deliveryStatus());
        assertEquals(1, admission.reservePublication(legacy, turn.id()).publicationSequence());
        assertThrows(ManagedStateException.class, () -> queue.enable(legacy));
    }
}
