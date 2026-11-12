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
package io.agentcore.managed.server.toolworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.control.SessionResolveResult;
import io.agentcore.managed.server.runtime.ManagedCatalog;
import io.agentcore.managed.server.runtime.managed.InMemoryManagedInputInbox;
import io.agentcore.managed.server.runtime.managed.InMemoryManagedLeaseStore;
import io.agentcore.managed.server.runtime.managed.InMemoryManagedMessageBus;
import io.agentcore.managed.server.runtime.managed.ManagedAdministrativeTools;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedAsyncToolExecutions;
import io.agentcore.managed.server.runtime.managed.ManagedBatchCodec;
import io.agentcore.managed.server.runtime.managed.ManagedCheckpointObjects;
import io.agentcore.managed.server.runtime.managed.ManagedDeliverySuspended;
import io.agentcore.managed.server.runtime.managed.ManagedHarnessRuntimeToolEvents;
import io.agentcore.managed.server.runtime.managed.ManagedHarnessRuntimeTurns;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentcore.managed.server.runtime.managed.ManagedLeaseStore;
import io.agentcore.managed.server.runtime.managed.ManagedMcpCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedPushDelivery;
import io.agentcore.managed.server.runtime.managed.ManagedResumePublisher;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeWriter;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedToolCommand;
import io.agentcore.managed.server.runtime.managed.ManagedToolCommandPublisher;
import io.agentcore.managed.server.runtime.managed.ManagedToolCommitGate.Decision;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutions;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor;
import io.agentcore.managed.server.runtime.managed.ManagedToolResultPublisher;
import io.agentcore.managed.server.runtime.managed.ManagedToolWorkerCommands;
import io.agentcore.managed.server.runtime.managed.ManagedToolWorkerLoop;
import io.agentcore.managed.server.runtime.managed.ManagedToolWorkerStore;
import io.agentcore.managed.server.runtime.managed.ManagedTurnExecution;
import io.agentcore.managed.server.runtime.managed.ManagedTurnPublisher;
import io.agentcore.managed.server.runtime.managed.SessionCommitTransactions;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import io.agentscope.core.session.SessionEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

/** Real Sandbox processes across independent execution/runtime ledgers and at-least-once MQ. */
@Timeout(30)
@EnabledOnOs({OS.LINUX, OS.MAC})
class ManagedToolWorkerPipelineTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    final ManagedScope scope =
            new ManagedScope("tenant", "namespace", "session_" + UUID.randomUUID());
    final ManagedMessageBus bus = messageBus();
    final ManagedLeaseStore leases = leaseStore();

    ManagedMessageBus messageBus() {
        return new InMemoryManagedMessageBus(() -> System.nanoTime() / 1_000_000, 8);
    }

    ManagedLeaseStore leaseStore() {
        return new InMemoryManagedLeaseStore(() -> System.nanoTime() / 1_000_000);
    }

    Duration awaitTimeout() {
        return Duration.ofSeconds(15);
    }

    DataSource database(String role) {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:"
                        + role
                        + "-"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    final ScheduledExecutorService renewals = Executors.newScheduledThreadPool(3);
    final List<AutoCloseable> closing = new ArrayList<>();
    final ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
    DataSource runtimeSource;
    ManagedAdmissionStore admission;
    ManagedJournalStore journal;
    ManagedToolWorkerStore physical;
    ManagedBatchCodec codec;
    ManagedRuntimeWriter writer;
    ManagedToolResultPublisher results;
    ManagedToolWorkerCommands commands;
    ManagedTurnPublisher turns;
    String turn;
    String task;

    @BeforeEach
    void setup() {
        var source = database("brain");
        runtimeSource = source;
        ManagedAdmissionStore.initialize(source);
        admission = new ManagedAdmissionStore(source, mapper);
        admission.createSession(scope, "{}");
        journal =
                new ManagedJournalStore(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(mapper, root.resolve("checkpoints")));
        var executionSource = database("hands");
        ManagedAdmissionStore.initialize(executionSource);
        physical = new ManagedToolWorkerStore(executionSource, mapper);
        codec = new ManagedBatchCodec(mapper, root.resolve("blobs"));
        writer = new ManagedRuntimeWriter(journal, new SessionCommitTransactions(journal));
        results = new ManagedToolResultPublisher(physical, bus, codec);
        results.start();
        closing.add(results);
        var resume = new ManagedResumePublisher(journal.suspensions(), bus, leases, mapper);
        resume.start();
        closing.add(resume);
        var executor = new ManagedSandboxToolExecutor(mapper, this::control);
        var work =
                new ManagedAsyncToolExecutions(
                        leases,
                        physical,
                        results,
                        renewals,
                        Duration.ofSeconds(5),
                        Duration.ofMillis(500),
                        Duration.ofMillis(20));
        var loop =
                new ManagedToolWorkerLoop(
                        physical,
                        work,
                        4,
                        Duration.ofSeconds(5),
                        Duration.ofMillis(100),
                        (s, t) -> executor,
                        (ref, error) -> errors.add(error),
                        16384,
                        64,
                        Duration.ofMillis(100));
        loop.start();
        closing.add(loop);
        commands =
                new ManagedToolWorkerCommands(
                        physical, codec, results, loop, journal.transport()::confirm);
        closing.add(bus.subscribe(Channel.TOOL_EXECUTE, 4, commands, (d, e) -> errors.add(e)));
        closing.add(bus.subscribe(Channel.TOOL_CONTROL, 2, commands, (d, e) -> errors.add(e)));
        var publications = new ManagedToolCommandPublisher(runtimeSource, bus, codec, mapper);
        journal.transport().onPublicationReady(publications::wakeup);
        publications.start();
        closing.add(publications);
        journal.transport().start(bus, codec);
        closing.add(journal.transport());
        turns = new ManagedTurnPublisher(admission, bus, leases, mapper, Duration.ofSeconds(5));
        turn = admission.acceptTurn(scope, "actor", "first", "{}").id();
        task = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "operation");
    }

    @AfterEach
    void stop() throws Exception {
        for (int i = closing.size() - 1; i >= 0; i--) closing.get(i).close();
        renewals.shutdownNow();
        bus.close();
    }

    String control(String command) throws Exception {
        Path output = Files.createTempFile("managed-pipeline-control-", ".json");
        var process =
                new ProcessBuilder("sh")
                        .directory(root.toFile())
                        .redirectOutput(output.toFile())
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(command.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            return Files.readString(output);
        } finally {
            process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }

    SessionEvent checkpoint(ManagedTurnExecution execution) {
        return new SessionEvent(
                1,
                "state_" + UUID.randomUUID(),
                execution.head().sequence() + 1,
                System.currentTimeMillis(),
                "state/checkpoint",
                "run",
                execution.turnId(),
                true,
                "{\"stateJson\":\"{\\\"context\\\":[]}\"}");
    }

    void run(ManagedTurnExecution execution) throws Exception {
        if (execution.turnId().equals(turn) && journal.tool(scope, task).isEmpty()) {
            String args =
                    mapper.writeValueAsString(
                            Map.of(
                                    "command",
                                    "printf 'started\\n"
                                            + "' >> once; printf 'before'; sleep 1; printf 'after'",
                                    "timeout_seconds",
                                    5));
            execution.boundary(
                    "intent_" + task,
                    List.of(checkpoint(execution)),
                    List.of(new ToolIntent("operation", "sandbox.exec", args)),
                    List.of(),
                    null);
            execution.suspend(task);
            return;
        }
        if (execution.turnId().equals(turn)) {
            assertEquals("succeeded", journal.tool(scope, task).orElseThrow().status());
            execution.toolCompleted(task, journal.tool(scope, task).orElseThrow().result());
        }
        execution.boundary(
                "finish_" + UUID.randomUUID(),
                List.of(checkpoint(execution)),
                List.of(),
                List.of(),
                null);
        execution.closeInputs();
        execution.finish("succeeded");
    }

    List<AutoCloseable> harnessRuntimes(CountDownLatch events) {
        var harnessRuntime =
                new ManagedHarnessRuntimeTurns(
                        bus,
                        leases,
                        admission,
                        journal,
                        writer,
                        mapper,
                        renewals,
                        Duration.ofSeconds(5),
                        Duration.ofMillis(500),
                        1,
                        Duration.ofMillis(500));
        var list = new ArrayList<AutoCloseable>();
        list.add(harnessRuntime);
        list.add(
                bus.subscribe(
                        Channel.TURNS,
                        4,
                        d -> harnessRuntime.consume(d, this::run),
                        (d, e) -> errors.add(e)));
        list.add(
                bus.subscribe(
                        Channel.TURN_RESUMES,
                        4,
                        d -> harnessRuntime.consume(d, this::run),
                        (d, e) -> errors.add(e)));
        var receiver = new ManagedHarnessRuntimeToolEvents(codec, journal, r -> {});
        list.add(
                bus.subscribe(
                        Channel.TOOL_EVENTS,
                        4,
                        d -> {
                            if (events.getCount() != 0)
                                throw new ManagedDeliverySuspended(Duration.ofMillis(100));
                            receiver.consume(d);
                        },
                        (d, e) -> errors.add(e)));
        closing.addAll(list);
        return list;
    }

    @Test
    void sandboxCompletesWithoutHarnessRuntimeAndReplaysItsOriginalResultToReplacement()
            throws Exception {
        // The stopped HarnessRuntime's Inbox is unavailable throughout this fault window, including
        // while the real SDK drains its transport during graceful close.
        var first = harnessRuntimes(new CountDownLatch(1));
        turns.publish(scope, turn);
        String second = admission.acceptTurn(scope, "actor", "second", "{}").id();
        turns.publish(scope, second);
        await(() -> "waiting_tool".equals(admission.turn(scope, turn).status()));
        for (var item : first) item.close();
        closing.removeAll(first);
        await(
                () ->
                        physical.tool(scope, task)
                                .map(t -> "succeeded".equals(t.status()))
                                .orElse(false));
        assertNull(journal.tool(scope, task).orElseThrow().result());
        assertEquals("accepted", admission.turn(scope, second).status());
        harnessRuntimes(new CountDownLatch(0));
        await(() -> "succeeded".equals(admission.turn(scope, second).status()));
        assertEquals(1, Files.readAllLines(root.resolve("once")).size());
        assertEquals("succeeded", admission.turn(scope, turn).status());
        var original = journal.tool(scope, task).orElseThrow();
        bus.publish(
                Channel.TOOL_EXECUTE,
                codec.encode(
                        new ManagedToolCommand(
                                scope, "execute_" + task, "execute", original, null)));
        Thread.sleep(100);
        assertEquals(1, Files.readAllLines(root.resolve("once")).size());
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void brainPublishesNoResumeUntilIndependentEventInboxCommits() throws Exception {
        var gate = new CountDownLatch(1);
        harnessRuntimes(gate);
        turns.publish(scope, turn);
        await(
                () ->
                        physical.tool(scope, task)
                                .map(t -> "succeeded".equals(t.status()))
                                .orElse(false));
        assertEquals("waiting_tool", admission.turn(scope, turn).status());
        assertNull(journal.tool(scope, task).orElseThrow().result());
        assertTrue(journal.suspensions().pending(10).isEmpty());
        gate.countDown();
        await(() -> "succeeded".equals(admission.turn(scope, turn).status()));
        assertEquals(1, Files.readAllLines(root.resolve("once")).size());
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void workspaceSnapshotAndRestoreUseTheSameMessageBoundaryWithoutNativeWritesFromToolWorker()
            throws Exception {
        var crypto = new VaultCrypto(new MockEnvironment(), "01234567890123456789012345678901");
        var targets = new ManagedMcpCatalog(runtimeSource, mapper, crypto, journal.transport());
        var catalog = mock(ManagedCatalog.class);
        when(catalog.resolve(scope))
                .thenReturn(
                        new SessionResolveResult(
                                null, Map.of(), null, null, null, Map.of(), null, List.of(),
                                List.of(), null, Map.of()));
        var administrative =
                new ManagedAdministrativeTools(
                        runtimeSource, mapper, journal.transport(), targets, catalog);
        closing.add(
                bus.subscribe(
                        Channel.TOOL_EVENTS,
                        2,
                        d -> assertTrue(administrative.receive(codec.decodeToolEvent(d.message()))),
                        (d, e) -> errors.add(e)));
        Files.writeString(root.resolve("workspace.txt"), "original");
        var snapshot =
                new ToolTask(
                        "snapshot",
                        "administrative",
                        "snapshot",
                        "snapshot",
                        "workspace.snapshot",
                        "{}",
                        0,
                        "ready",
                        null,
                        null,
                        null);
        var executor = administrative.executor(scope);
        executor.start(scope, snapshot);
        await(() -> executor.query(scope, snapshot).state() == ManagedToolExecutor.State.SUCCEEDED);
        var captured = executor.query(scope, snapshot).result();
        Files.writeString(root.resolve("workspace.txt"), "changed");
        var restore =
                new ToolTask(
                        "restore",
                        "administrative",
                        "restore",
                        "restore",
                        "workspace.restore",
                        mapper.writeValueAsString(Map.of("snapshot", mapper.readTree(captured))),
                        0,
                        "ready",
                        null,
                        null,
                        null);
        executor.start(scope, restore);
        await(() -> executor.query(scope, restore).state() == ManagedToolExecutor.State.SUCCEEDED);
        assertEquals("original", Files.readString(root.resolve("workspace.txt")));
        assertEquals(0, journal.head(scope).sequence());
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void cancelBeforeExecuteCannotLaunchAndUnknownExecutionCannotBeRestarted() throws Exception {
        var value =
                new ToolTask(
                        "cancelled",
                        "turn",
                        "call",
                        "cancelled",
                        "sandbox.exec",
                        "{}",
                        1,
                        "ready",
                        null,
                        null,
                        null);
        var cancel = new ManagedToolCommand(scope, "cancel", "cancel", value, null);
        commands.consume(delivery(Channel.TOOL_CONTROL, cancel));
        // This fixture isolates Worker cancellation after durable admission; authorization races
        // use the real Runtime gate in ManagedToolSubmissionTest.
        physical.receive(new ManagedToolCommand(scope, "execute", "execute", value, null));
        await(() -> "cancelled".equals(physical.tool(scope, value.id()).orElseThrow().status()));
        var starts = new AtomicInteger();
        var unknown =
                new ManagedToolExecutor() {
                    public String start(ManagedScope s, ToolTask t) {
                        starts.incrementAndGet();
                        return t.executionId();
                    }

                    public Outcome query(ManagedScope s, ToolTask t) {
                        return new Outcome(State.UNKNOWN, null);
                    }

                    public Outcome cancel(ManagedScope s, ToolTask t) {
                        return query(s, t);
                    }
                };
        var work =
                new ManagedToolExecutions(
                        leases,
                        new InMemoryManagedInputInbox(System::currentTimeMillis),
                        physical,
                        results,
                        renewals,
                        Duration.ofSeconds(5),
                        Duration.ofMillis(500),
                        Duration.ofMillis(20));
        var driver =
                new ManagedToolWorkerCommands(
                        physical,
                        codec,
                        results,
                        (s, t) -> work.execute(s, t, unknown),
                        ignoredCommand -> Decision.COMMITTED);
        var task =
                new ToolTask(
                        "unknown",
                        "turn",
                        "call2",
                        "unknown",
                        "sandbox.exec",
                        "{}",
                        1,
                        "ready",
                        null,
                        null,
                        null);
        var execute =
                delivery(
                        Channel.TOOL_EXECUTE,
                        new ManagedToolCommand(scope, "execute_unknown", "execute", task, null));
        driver.consume(execute);
        execute.handoff().confirm();
        driver.consume(execute);
        assertEquals(1, starts.get());
        assertEquals("unknown", physical.tool(scope, task.id()).orElseThrow().status());
    }

    ManagedPushDelivery delivery(Channel channel, ManagedToolCommand command) {
        return new ManagedPushDelivery(
                "d", "c", channel, "topic", "group", "broker", 1, codec.encode(command));
    }

    void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + awaitTimeout().toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < until) {
            if (!errors.isEmpty()) fail(errors.toString());
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), errors::toString);
    }
}
