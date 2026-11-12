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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedToolCommitGate.Decision;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Admission ACK, bounded owners, durable scans and lease-expiry recovery without MQ redelivery. */
@Timeout(15)
class ManagedToolWorkerLoopTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    final ManagedScope scope = new ManagedScope("tenant", "namespace", "session");
    final Duration ttl = leaseTtl();
    final InMemoryManagedLeaseStore leases =
            new InMemoryManagedLeaseStore(System::currentTimeMillis);
    final InMemoryManagedMessageBus bus =
            new InMemoryManagedMessageBus(System::currentTimeMillis, 4);
    final ScheduledExecutorService renewals = Executors.newScheduledThreadPool(2);
    final List<ManagedToolWorkerLoop> loops = new ArrayList<>();
    final List<RuntimeException> failures = new CopyOnWriteArrayList<>();
    final AtomicInteger starts = new AtomicInteger();
    final AtomicInteger preparations = new AtomicInteger();
    final AtomicInteger queries = new AtomicInteger();
    final Set<String> launched = ConcurrentHashMap.newKeySet();
    final ConcurrentHashMap<String, CompletableFuture<ManagedToolExecutor.Outcome>> remote =
            new ConcurrentHashMap<>();
    volatile boolean declinePreparation;
    volatile boolean failStart;
    volatile boolean failPreparation;
    Runnable duringPreparation = () -> {};
    ManagedToolWorkerStore store;
    ManagedToolResultPublisher results;
    ManagedBatchCodec codec;
    JdbcTemplate jdbc;

    protected Duration leaseTtl() {
        return Duration.ofMillis(300);
    }

    protected DataSource database() throws Exception {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:owned-tools-"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    protected void closeDatabase() throws Exception {}

    @BeforeEach
    void setup() throws Exception {
        var source = database();
        ManagedAdmissionStore.initialize(source);
        jdbc = new JdbcTemplate(source);
        store = new ManagedToolWorkerStore(source, mapper);
        codec = new ManagedBatchCodec(mapper, root);
        results = new ManagedToolResultPublisher(store, bus, codec);
        results.start();
    }

    @AfterEach
    void close() throws Exception {
        loops.forEach(ManagedToolWorkerLoop::close);
        results.close();
        renewals.shutdownNow();
        bus.close();
        closeDatabase();
    }

    @Test
    void repairRepublishesOriginalToolWithoutPreparingOrStartingIt() throws Exception {
        var task = task("repair-mq");
        store.receive(new ManagedToolCommand(scope, "cmd", "execute", task, "{}"));
        jdbc.update("UPDATE ma.hands_execution SET last_dispatched_at=0");
        var published = new CountDownLatch(1);
        var owner = loop(1);
        owner.republication(
                (s, id) -> {
                    assertEquals(scope, s);
                    assertEquals(task.id(), id);
                    published.countDown();
                });
        owner.start();
        assertTrue(published.await(3, TimeUnit.SECONDS));
        assertEquals(0, starts.get());
        assertEquals(0, preparations.get());
        assertEquals("ready", store.tool(scope, task.id()).orElseThrow().status());
    }

    ToolTask task(String id) {
        remote.putIfAbsent(id, new CompletableFuture<>());
        return new ToolTask(id, "turn", id, id, "mcp.call", "{}", 1, "ready", null, null, null);
    }

    ManagedToolExecutor provider() {
        duringPreparation.run();
        if (failPreparation) throw new ManagedTransportException("Sandbox unavailable");
        if (declinePreparation) return null;
        return new ManagedToolExecutor() {
            public boolean idempotentStart() {
                return true;
            }

            public String start(ManagedScope owner, ToolTask task) {
                if (launched.add(task.id())) starts.incrementAndGet();
                if (failStart) throw new ManagedTransportException("Start response lost");
                return task.executionId();
            }

            public CompletionStage<?> completion(ManagedScope owner, ToolTask task) {
                return remote.get(task.id());
            }

            public Outcome query(ManagedScope owner, ToolTask task) {
                assertFalse(Thread.currentThread().getName().equals("test-mcp-transport"));
                queries.incrementAndGet();
                if (!launched.contains(task.id())) return new Outcome(State.NOT_FOUND, null);
                return remote.get(task.id()).getNow(new Outcome(State.RUNNING, null));
            }

            public Outcome cancel(ManagedScope owner, ToolTask task) {
                return new Outcome(State.CANCELLED, "{}");
            }
        };
    }

    ManagedToolWorkerLoop loop(int concurrency) {
        return loop(concurrency, 16384);
    }

    ManagedToolWorkerLoop loop(int concurrency, int inflight) {
        var work =
                new ManagedAsyncToolExecutions(
                        leases,
                        store,
                        results,
                        renewals,
                        ttl,
                        Duration.ofMillis(50),
                        Duration.ofMillis(10));
        var loop =
                new ManagedToolWorkerLoop(
                        store,
                        work,
                        concurrency,
                        ttl,
                        Duration.ofMillis(50),
                        (s, t) -> {
                            preparations.incrementAndGet();
                            return provider();
                        },
                        (ref, error) -> failures.add(error),
                        inflight,
                        64,
                        Duration.ofMillis(50));
        loops.add(loop);
        return loop;
    }

    void admit(ToolTask task, BiConsumer<ManagedScope, String> wakeup) {
        var command =
                new ManagedToolCommand(scope, "execute_" + task.id(), "execute", task, "sealed");
        new ManagedToolWorkerCommands(
                        store, codec, results, wakeup, ignoredCommand -> Decision.COMMITTED)
                .consume(delivery(Channel.TOOL_EXECUTE, command));
    }

    ManagedPushDelivery delivery(Channel channel, ManagedToolCommand command) {
        return new ManagedPushDelivery(
                "delivery",
                "consumer",
                channel,
                "topic",
                "group",
                "broker",
                1,
                codec.encode(command),
                ManagedDeliveryHandoff.direct());
    }

    boolean finished(String id) {
        return store.tool(scope, id).map(t -> "succeeded".equals(t.status())).orElse(false);
    }

    @Test
    void durableAdmissionReturnsBeforeExecutionAndScanRepairsLostWakeup() throws Exception {
        var task = task("accepted-before-start");
        admit(
                task,
                (s, t) -> {
                    throw new IllegalStateException("owner stopped after admission");
                });
        assertEquals("ready", store.tool(scope, task.id()).orElseThrow().status());
        assertEquals(0, starts.get());
        remote.get(task.id())
                .complete(
                        new ManagedToolExecutor.Outcome(ManagedToolExecutor.State.SUCCEEDED, "{}"));
        loop(1).start();
        await(() -> finished(task.id()));
        assertEquals(1, starts.get());
    }

    @Test
    void healthyOwnerRenewsAcrossMultipleLeasePeriodsWithoutPeerClaims() throws Exception {
        var task = task("healthy-owner");
        admit(task, (s, t) -> {});
        loop(1).start();
        loop(1).start();
        await(() -> starts.get() == 1);
        Thread.sleep(ttl.toMillis() * 3);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT owner_generation FROM ma.hands_execution WHERE task_id=?",
                        Long.class,
                        task.id()));
        assertEquals(
                0,
                store.history(scope, task.id()).stream()
                        .filter("collector_claimed"::equals)
                        .count());
        remote.get(task.id())
                .complete(
                        new ManagedToolExecutor.Outcome(ManagedToolExecutor.State.SUCCEEDED, "{}"));
        await(() -> finished(task.id()));
        assertEquals(1, starts.get());
    }

    @Test
    void replacementRecoversAnAckedExecutionAfterTheAbandonedLeaseExpires() throws Exception {
        var task = task("crashed-owner");
        admit(task, (s, t) -> {});
        var abandoned = leases.acquire(scope.key("tool:" + task.id()), ttl).orElseThrow();
        var claim = store.claimTool(scope, task.id(), abandoned.token());
        provider().start(scope, claim.task());
        var original =
                store.recordToolHandle(scope, task.id(), abandoned.token(), task.executionId());
        store.toolEvents().open(scope, original, abandoned.token(), false);
        store.toolHeartbeat(scope, task.id(), abandoned.token(), System.currentTimeMillis());
        remote.get(task.id())
                .complete(
                        new ManagedToolExecutor.Outcome(
                                ManagedToolExecutor.State.SUCCEEDED, "{\"original\":true}"));
        loop(1).start();
        assertFalse(finished(task.id()));
        await(() -> finished(task.id()));
        assertFalse(leases.owns(abandoned));
        assertEquals(1, starts.get());
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT owner_generation FROM ma.hands_execution WHERE task_id=?",
                        Long.class,
                        task.id()));
        assertTrue(store.tool(scope, task.id()).orElseThrow().result().contains("original"));
    }

    @Test
    void oneWorkerStartsManyOutstandingToolsAndWaitsForNotificationsWithoutPolling()
            throws Exception {
        var owner = loop(1);
        owner.start();
        var ids = new ArrayList<String>();
        for (int i = 0; i < 12; i++) {
            var task = task("async-" + i);
            ids.add(task.id());
            admit(task, owner::wakeup);
        }
        await(() -> starts.get() == ids.size() && queries.get() == ids.size());
        assertTrue(ids.stream().noneMatch(this::finished));
        int before = queries.get();
        Thread.sleep(ttl.toMillis() * 3);
        assertEquals(
                before, queries.get(), "Pending futures must not cause periodic result queries");
        var callback =
                new Thread(
                        () ->
                                ids.forEach(
                                        id ->
                                                remote.get(id)
                                                        .complete(
                                                                new ManagedToolExecutor.Outcome(
                                                                        ManagedToolExecutor.State
                                                                                .SUCCEEDED,
                                                                        "{}"))),
                        "test-mcp-transport");
        callback.start();
        callback.join();
        await(() -> ids.stream().allMatch(this::finished));
        assertEquals(ids.size(), starts.get());
    }

    @Test
    void cancellationUsesTheIndependentControlLaneWhileTheExecutionSlotIsBusy() throws Exception {
        var owner = loop(1);
        owner.start();
        var task = task("cancel-running");
        admit(task, owner::wakeup);
        await(() -> starts.get() == 1);
        var cancel = new ManagedToolCommand(scope, "cancel_" + task.id(), "cancel", task, null);
        new ManagedToolWorkerCommands(
                        store, codec, results, owner::wakeup, ignoredCommand -> Decision.COMMITTED)
                .consume(delivery(Channel.TOOL_CONTROL, cancel));
        await(() -> "cancelled".equals(store.tool(scope, task.id()).orElseThrow().status()));
        assertEquals(1, starts.get());
    }

    @Test
    void stoppedAsyncOwnerReleasesItsLeaseAndReplacementReusesTheOriginalCall() throws Exception {
        var task = task("stop-pending-future");
        admit(task, (s, t) -> {});
        var old = loop(1);
        old.start();
        await(() -> starts.get() == 1 && queries.get() == 1);
        old.close();
        remote.get(task.id())
                .complete(
                        new ManagedToolExecutor.Outcome(
                                ManagedToolExecutor.State.SUCCEEDED, "{\"original\":true}"));
        loop(1).start();
        await(() -> finished(task.id()));
        assertEquals(1, starts.get());
        assertTrue(store.tool(scope, task.id()).orElseThrow().result().contains("original"));
    }

    @Test
    void staleOwnerHeartbeatCannotPostponeItsSuccessorsRecovery() {
        var task = task("fenced-heartbeat");
        admit(task, (s, t) -> {});
        store.claimTool(scope, task.id(), "old");
        store.claimTool(scope, task.id(), "new");
        long before =
                jdbc.queryForObject(
                        "SELECT last_dispatched_at FROM ma.hands_execution WHERE task_id=?",
                        Long.class,
                        task.id());
        store.toolHeartbeat(scope, task.id(), "old", before + 10000);
        assertEquals(
                before,
                jdbc.queryForObject(
                        "SELECT last_dispatched_at FROM ma.hands_execution WHERE task_id=?",
                        Long.class,
                        task.id()));
        store.toolHeartbeat(scope, task.id(), "new", before + 100);
        assertEquals(
                before + 100,
                jdbc.queryForObject(
                        "SELECT last_dispatched_at FROM ma.hands_execution WHERE task_id=?",
                        Long.class,
                        task.id()));
    }

    @Test
    void admissionDatabaseFailureDoesNotAcknowledgeOrWakeAnExecutor() {
        var unavailable = mock(ManagedToolWorkerStore.class);
        var wakeup = mock(BiConsumer.class);
        doThrow(new DataAccessResourceFailureException("database offline"))
                .when(unavailable)
                .receive(any());
        var task = task("not-admitted");
        var command =
                new ManagedToolCommand(scope, "execute_" + task.id(), "execute", task, "sealed");
        var commands =
                new ManagedToolWorkerCommands(
                        unavailable, codec, results, wakeup, ignoredCommand -> Decision.COMMITTED);
        assertThrows(
                ManagedDeliverySuspended.class,
                () -> commands.consume(delivery(Channel.TOOL_EXECUTE, command)));
        verifyNoInteractions(wakeup);
    }

    ManagedPushDelivery pendingDelivery(ManagedToolWorkerLoop owner, ToolTask task) {
        var handoff = owner.receiveCapacity().reserve(1).get(0);
        var command =
                new ManagedToolCommand(scope, "execute_" + task.id(), "execute", task, "sealed");
        return new ManagedPushDelivery(
                UUID.randomUUID().toString(),
                "consumer",
                Channel.TOOL_EXECUTE,
                "topic",
                "group",
                "broker",
                1,
                codec.encode(command),
                handoff);
    }

    @Test
    void declinedProviderLeavesFreshInvocationAvailableToAnEnvironmentWorker() throws Exception {
        declinePreparation = true;
        var owner = loop(1, 1);
        var task = task("external-worker");
        var delivery = pendingDelivery(owner, task);
        new ManagedToolWorkerCommands(store, codec, results, owner, c -> Decision.COMMITTED)
                .consume(delivery);
        delivery.handoff().confirm();
        await(() -> owner.receiveCapacity().availableExecutions() == 1);
        assertEquals("ready", store.tool(scope, task.id()).orElseThrow().status());
        assertEquals(0, starts.get());
        assertTrue(store.claimTool(scope, task.id(), "environment-worker").firstLaunch());
    }

    @Test
    void attemptedInvocationIsNotResetToFreshWhenItsResponseIsLost() throws Exception {
        failStart = true;
        var owner = loop(1, 1);
        var task = task("ambiguous-invocation");
        var delivery = pendingDelivery(owner, task);
        new ManagedToolWorkerCommands(store, codec, results, owner, c -> Decision.COMMITTED)
                .consume(delivery);
        delivery.handoff().confirm();
        long until = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (owner.receiveCapacity().availableExecutions() == 0 && System.nanoTime() < until)
            Thread.sleep(10);
        assertEquals(1, owner.receiveCapacity().availableExecutions());
        assertEquals("unknown", store.tool(scope, task.id()).orElseThrow().status());
        assertEquals(1, starts.get());
        assertFalse(store.claimTool(scope, task.id(), "replacement").firstLaunch());
    }

    @Test
    void toolClaimPrecedesAckButProviderAndInvocationWaitForConfirmation() throws Exception {
        var owner = loop(1, 1);
        var task = task("confirmed-only");
        var commands =
                new ManagedToolWorkerCommands(
                        store, codec, results, owner, c -> Decision.COMMITTED);
        var delivery = pendingDelivery(owner, task);
        commands.consume(delivery);
        assertEquals("starting", store.tool(scope, task.id()).orElseThrow().status());
        assertEquals(0, preparations.get());
        assertEquals(0, starts.get());
        assertEquals(0, owner.receiveCapacity().availableExecutions());
        assertTrue(owner.receiveCapacity().reserve(1).isEmpty());
        delivery.handoff().confirm();
        await(() -> starts.get() == 1);
        assertEquals(0, owner.receiveCapacity().availableExecutions());
        assertEquals(1, owner.receiveCapacity().availableHandoffs());
        remote.get(task.id())
                .complete(
                        new ManagedToolExecutor.Outcome(ManagedToolExecutor.State.SUCCEEDED, "{}"));
        await(() -> finished(task.id()) && owner.receiveCapacity().availableExecutions() == 1);
        assertEquals(1, preparations.get());
    }

    @Test
    void duplicateToolDeliveryDoesNotPrepareOrClaimTheSameOwnerAgain() throws Exception {
        var owner = loop(1, 2);
        var task = task("duplicate-handoff");
        var commands =
                new ManagedToolWorkerCommands(
                        store, codec, results, owner, c -> Decision.COMMITTED);
        var first = pendingDelivery(owner, task);
        commands.consume(first);
        first.handoff().confirm();
        await(() -> starts.get() == 1);
        var duplicate = pendingDelivery(owner, task);
        commands.consume(duplicate);
        duplicate.handoff().confirm();
        assertEquals(1, preparations.get());
        assertEquals(1, owner.receiveCapacity().availableExecutions());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT owner_generation FROM ma.hands_execution WHERE task_id=?",
                        Long.class,
                        task.id()));
    }

    @Test
    void lostToolAckConfirmationTimesOutWithoutPreparationAndLateAckCannotStartIt()
            throws Exception {
        var owner = loop(1, 1);
        var task = task("lost-confirmation");
        var delivery = pendingDelivery(owner, task);
        new ManagedToolWorkerCommands(store, codec, results, owner, c -> Decision.COMMITTED)
                .consume(delivery);
        await(() -> owner.receiveCapacity().availableExecutions() == 1);
        assertEquals(0, preparations.get());
        assertEquals("ready", store.tool(scope, task.id()).orElseThrow().status());
        delivery.handoff().confirm();
        Thread.sleep(50);
        assertEquals(0, starts.get());
        assertEquals(1, owner.receiveCapacity().availableHandoffs());
        owner.start();
        await(() -> starts.get() == 1);
        assertEquals(1, preparations.get());
    }

    @Test
    void cancellationBetweenClaimAndAckPreventsPhysicalInvocation() throws Exception {
        var owner = loop(1, 1);
        var task = task("cancel-before-ack");
        var commands =
                new ManagedToolWorkerCommands(
                        store, codec, results, owner, c -> Decision.COMMITTED);
        var delivery = pendingDelivery(owner, task);
        commands.consume(delivery);
        commands.consume(
                delivery(
                        Channel.TOOL_CONTROL,
                        new ManagedToolCommand(
                                scope, "cancel_" + task.id(), "cancel", task, null)));
        assertEquals(0, starts.get());
        delivery.handoff().confirm();
        await(() -> "cancelled".equals(store.tool(scope, task.id()).orElseThrow().status()));
        assertEquals(0, starts.get());
    }

    @Test
    void cancellationBeforeUnavailableProviderDoesNotPrepareOrLaunch() throws Exception {
        failPreparation = true;
        var owner = loop(1, 1);
        var task = task("cancel-unavailable-provider");
        store.receive(new ManagedToolCommand(scope, "cancel", "cancel", task, null));
        var delivery = pendingDelivery(owner, task);
        new ManagedToolWorkerCommands(store, codec, results, owner, c -> Decision.COMMITTED)
                .consume(delivery);
        delivery.handoff().confirm();
        await(() -> "cancelled".equals(store.tool(scope, task.id()).orElseThrow().status()));
        assertEquals(0, preparations.get());
        assertEquals(0, starts.get());
        assertTrue(failures.isEmpty(), failures::toString);
    }

    @Test
    void cancellationDuringPreparationPreventsInvocation() throws Exception {
        var owner = loop(1, 1);
        var task = task("cancel-during-preparation");
        duringPreparation =
                () -> store.receive(new ManagedToolCommand(scope, "cancel", "cancel", task, null));
        var delivery = pendingDelivery(owner, task);
        new ManagedToolWorkerCommands(store, codec, results, owner, c -> Decision.COMMITTED)
                .consume(delivery);
        delivery.handoff().confirm();
        await(() -> "cancelled".equals(store.tool(scope, task.id()).orElseThrow().status()));
        assertEquals(1, preparations.get());
        assertEquals(0, starts.get());
        assertTrue(failures.isEmpty(), failures::toString);
    }

    @Test
    void cancellationAfterFailedPreparationSettlesWithoutRetryingProvider() throws Exception {
        failPreparation = true;
        var owner = loop(1, 1);
        var task = task("cancel-after-failed-preparation");
        var commands =
                new ManagedToolWorkerCommands(
                        store, codec, results, owner, c -> Decision.COMMITTED);
        var first = pendingDelivery(owner, task);
        commands.consume(first);
        first.handoff().confirm();
        awaitExpectedProviderFailure(owner);
        assertEquals("ready", store.tool(scope, task.id()).orElseThrow().status());
        failures.clear();
        store.receive(new ManagedToolCommand(scope, "cancel", "cancel", task, null));
        var retry = pendingDelivery(owner, task);
        commands.consume(retry);
        retry.handoff().confirm();
        await(() -> "cancelled".equals(store.tool(scope, task.id()).orElseThrow().status()));
        assertEquals(1, preparations.get());
        assertEquals(0, starts.get());
    }

    private void awaitExpectedProviderFailure(ManagedToolWorkerLoop owner) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while ((failures.isEmpty() || owner.receiveCapacity().availableExecutions() == 0)
                && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(1, owner.receiveCapacity().availableExecutions());
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).getMessage().contains("Sandbox unavailable"));
    }

    @Test
    void unavailableProviderCannotTurnUnknownExecutionIntoCancelled() throws Exception {
        failPreparation = true;
        var owner = loop(1, 1);
        var task = task("cancel-unknown-provider");
        store.receive(new ManagedToolCommand(scope, "execute", "execute", task, "{}"));
        jdbc.update("UPDATE ma.hands_execution SET status='unknown' WHERE task_id=?", task.id());
        store.receive(new ManagedToolCommand(scope, "cancel", "cancel", task, null));
        var delivery = pendingDelivery(owner, task);
        new ManagedToolWorkerCommands(store, codec, results, owner, c -> Decision.COMMITTED)
                .consume(delivery);
        delivery.handoff().confirm();
        awaitExpectedProviderFailure(owner);
        assertEquals("unknown", store.tool(scope, task.id()).orElseThrow().status());
        assertEquals(null, store.tool(scope, task.id()).orElseThrow().result());
        assertEquals(0, starts.get());
        assertEquals(1, preparations.get());
    }

    @Test
    void shutdownBeforeAckLeavesARecoverableIdentityAndNoProviderWork() throws Exception {
        var owner = loop(1, 1);
        var task = task("shutdown-before-ack");
        var delivery = pendingDelivery(owner, task);
        new ManagedToolWorkerCommands(store, codec, results, owner, c -> Decision.COMMITTED)
                .consume(delivery);
        owner.close();
        delivery.handoff().confirm();
        assertEquals(0, preparations.get());
        assertEquals(1, owner.receiveCapacity().availableExecutions());
        var replacement = loop(1, 1);
        replacement.start();
        await(() -> starts.get() == 1);
        remote.get(task.id())
                .complete(
                        new ManagedToolExecutor.Outcome(ManagedToolExecutor.State.SUCCEEDED, "{}"));
        await(() -> finished(task.id()));
        assertEquals(1, starts.get());
    }

    void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), failures::toString);
        assertTrue(failures.isEmpty(), failures::toString);
    }
}
