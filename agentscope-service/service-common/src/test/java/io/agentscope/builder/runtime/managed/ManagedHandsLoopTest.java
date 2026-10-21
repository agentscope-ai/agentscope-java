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
package io.agentscope.builder.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import io.agentscope.builder.runtime.managed.ManagedToolCommitGate.Decision;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
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
class ManagedHandsLoopTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    final ManagedScope scope = new ManagedScope("tenant", "namespace", "session");
    final Duration ttl = Duration.ofMillis(300);
    final InMemoryManagedLeaseStore leases =
            new InMemoryManagedLeaseStore(System::currentTimeMillis);
    final InMemoryManagedMessageBus bus =
            new InMemoryManagedMessageBus(System::currentTimeMillis, 4);
    final ScheduledExecutorService renewals = Executors.newScheduledThreadPool(2);
    final List<ManagedHandsLoop> loops = new ArrayList<>();
    final List<RuntimeException> failures = new CopyOnWriteArrayList<>();
    final AtomicInteger starts = new AtomicInteger();
    final ConcurrentHashMap<String, CompletableFuture<ManagedToolExecutor.Outcome>> remote =
            new ConcurrentHashMap<>();
    ManagedHandsStore store;
    ManagedToolResultPublisher results;
    ManagedBatchCodec codec;
    JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:owned-tools-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        jdbc = new JdbcTemplate(source);
        store = new ManagedHandsStore(source, mapper);
        codec = new ManagedBatchCodec(mapper, root);
        results = new ManagedToolResultPublisher(store, bus, codec);
        results.start();
    }

    @AfterEach
    void close() {
        loops.forEach(ManagedHandsLoop::close);
        results.close();
        renewals.shutdownNow();
        bus.close();
    }

    ToolTask task(String id) {
        remote.putIfAbsent(id, new CompletableFuture<>());
        return new ToolTask(id, "turn", id, id, "mcp.call", "{}", 1, "ready", null, null, null);
    }

    ManagedToolExecutor provider() {
        return new ManagedToolExecutor() {
            public String start(ManagedScope owner, ToolTask task) {
                starts.incrementAndGet();
                return task.executionId();
            }

            public Outcome query(ManagedScope owner, ToolTask task) {
                return remote.get(task.id()).getNow(new Outcome(State.RUNNING, null));
            }

            public Outcome cancel(ManagedScope owner, ToolTask task) {
                return new Outcome(State.CANCELLED, "{}");
            }
        };
    }

    ManagedHandsLoop loop(int concurrency) {
        var work =
                new ManagedToolExecutions(
                        leases,
                        new InMemoryManagedInputInbox(System::currentTimeMillis),
                        store,
                        results,
                        renewals,
                        ttl,
                        Duration.ofMillis(50),
                        Duration.ofMillis(10));
        var loop =
                new ManagedHandsLoop(
                        store,
                        work,
                        concurrency,
                        ttl,
                        Duration.ofMillis(50),
                        (s, t) -> provider(),
                        (ref, error) -> failures.add(error));
        loops.add(loop);
        return loop;
    }

    void admit(ToolTask task, BiConsumer<ManagedScope, String> wakeup) {
        var command =
                new ManagedToolCommand(scope, "execute_" + task.id(), "execute", task, "sealed");
        new ManagedHandsCommands(
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
                codec.encode(command));
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
    void saturationKeepsAckedTasksDurableUntilAnExecutionSlotIsAvailable() throws Exception {
        var owner = loop(1);
        owner.start();
        var first = task("first");
        admit(first, owner::wakeup);
        await(() -> starts.get() == 1);
        var second = task("second");
        admit(second, owner::wakeup);
        admit(second, owner::wakeup);
        assertEquals("ready", store.tool(scope, second.id()).orElseThrow().status());
        assertEquals(1, starts.get());
        remote.get(first.id())
                .complete(
                        new ManagedToolExecutor.Outcome(ManagedToolExecutor.State.SUCCEEDED, "{}"));
        await(() -> starts.get() == 2);
        remote.get(second.id())
                .complete(
                        new ManagedToolExecutor.Outcome(ManagedToolExecutor.State.SUCCEEDED, "{}"));
        await(() -> finished(first.id()) && finished(second.id()));
        assertEquals(2, starts.get());
    }

    @Test
    void cancellationUsesTheIndependentControlLaneWhileTheExecutionSlotIsBusy() throws Exception {
        var owner = loop(1);
        owner.start();
        var task = task("cancel-running");
        admit(task, owner::wakeup);
        await(() -> starts.get() == 1);
        var cancel = new ManagedToolCommand(scope, "cancel_" + task.id(), "cancel", task, null);
        new ManagedHandsCommands(
                        store, codec, results, owner::wakeup, ignoredCommand -> Decision.COMMITTED)
                .consume(delivery(Channel.TOOL_CONTROL, cancel));
        await(() -> "cancelled".equals(store.tool(scope, task.id()).orElseThrow().status()));
        assertEquals(1, starts.get());
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
        var unavailable = mock(ManagedHandsStore.class);
        var wakeup = mock(BiConsumer.class);
        doThrow(new DataAccessResourceFailureException("database offline"))
                .when(unavailable)
                .receive(any());
        var task = task("not-admitted");
        var command =
                new ManagedToolCommand(scope, "execute_" + task.id(), "execute", task, "sealed");
        var commands =
                new ManagedHandsCommands(
                        unavailable, codec, results, wakeup, ignoredCommand -> Decision.COMMITTED);
        assertThrows(
                ManagedDeliverySuspended.class,
                () -> commands.consume(delivery(Channel.TOOL_EXECUTE, command)));
        verifyNoInteractions(wakeup);
    }

    void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), failures::toString);
        assertTrue(failures.isEmpty(), failures::toString);
    }
}
