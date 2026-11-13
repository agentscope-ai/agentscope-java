/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.durable.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.OutputPage;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ToolCall;
import io.agentscope.durable.ExecutionMessages;
import io.agentscope.durable.Executor;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerRuntimeTest {
    @TempDir Path directory;
    private final ExecutionRequest request =
            new ExecutionRequest(
                    "execution",
                    new ThreadKey(new SessionKey("namespace", "session"), "main"),
                    "turn",
                    new ToolCall("call", "remote", "job", DurableJson.value(Map.of())));

    @Test
    void exposesStableLocalExecutorWithoutBeingAnExecutor() {
        try (var worker = new WorkerRuntime(mock(ExecutionStore.class), Map.of())) {
            assertFalse(Executor.class.isAssignableFrom(WorkerRuntime.class));
            assertSame(worker.localExecutor(), worker.localExecutor());
            assertEquals(
                    new Executor.Capabilities(true, true, true, true),
                    worker.localExecutor().capabilities());
        }
    }

    @Test
    void forcedCloseReleasesQueuedCapacityWithoutDispatchAndReplacementContinues()
            throws Exception {
        var held = new CountDownLatch(2);
        var recovered =
                new ExecutionRequest(
                        "recovery", request.thread(), request.turnId(), request.call());
        Executor provider = mock(Executor.class);
        when(provider.capabilities()).thenReturn(new Executor.Capabilities(false, true, true));
        when(provider.startExecution(request))
                .thenReturn(
                        new ExecutionResult(
                                ExecutionState.SUCCEEDED, "one", DurableJson.value("done"), null));
        when(provider.query(recovered, "original"))
                .thenReturn(
                        new ExecutionResult(
                                ExecutionState.SUCCEEDED,
                                "original",
                                DurableJson.value("recovered"),
                                null));
        try (var records = JdbcRecordStore.sqlite(directory.resolve("queued-close.db"))) {
            var store = new TransactionalExecutionStore(records);
            store.admit(request);
            store.admit(recovered);
            var previous = store.claim(recovered, "old", Duration.ofSeconds(1)).orElseThrow();
            store.begin(previous);
            store.update(
                    previous, new ExecutionResult(ExecutionState.RUNNING, "original", null, null));
            store.release(previous, Duration.ZERO);
            try (var worker =
                    WorkerRuntime.builder()
                            .executionStore(store)
                            .executor("remote", provider)
                            .parallelism(1)
                            .recoveryParallelism(1)
                            .pollInterval(Duration.ofMillis(10))
                            .build()) {
                // Hold the pool threads before discovery to reproduce shutdownNow returning jobs
                // that have been counted as active but have not entered their run/finally block.
                for (String name : List.of("workers", "recoveries")) {
                    var field = WorkerRuntime.class.getDeclaredField(name);
                    field.setAccessible(true);
                    ((ExecutorService) field.get(worker))
                            .execute(
                                    () -> {
                                        held.countDown();
                                        try {
                                            new CountDownLatch(1).await();
                                        } catch (InterruptedException stopped) {
                                            Thread.currentThread().interrupt();
                                        }
                                    });
                }
                assertTrue(held.await(5, TimeUnit.SECONDS));
                worker.start();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (worker.status().activeStarts() != 1
                        || worker.status().activeRecoveries() != 1) {
                    assertTrue(System.nanoTime() < until, "Discovery did not queue both lanes");
                    Thread.sleep(10);
                }
                worker.close();
                assertTrue(worker.drain(Duration.ofMillis(100)));
                assertEquals(0, worker.status().activeStarts());
                assertEquals(0, worker.status().activeRecoveries());
                verify(provider, never()).startExecution(any());
                verify(provider, never()).query(any(), any());
                verify(provider, never()).close();
            }
            try (var replacement = new WorkerRuntime(store, Map.of("remote", provider))) {
                replacement.tick();
                assertEquals(
                        ExecutionState.SUCCEEDED,
                        replacement.localExecutor().query(request, null).state());
                assertEquals(
                        ExecutionState.SUCCEEDED,
                        replacement.localExecutor().query(recovered, null).state());
                verify(provider, times(1)).startExecution(request);
                verify(provider, never()).startExecution(recovered);
            }
        }
    }

    @Test
    void saturatedStartsDoNotBlockRecoveryAndDrainPreservesActiveLeases() throws Exception {
        var running = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var renewed = new CountDownLatch(1);
        var recovered =
                new ExecutionRequest(
                        "previous", request.thread(), request.turnId(), request.call());
        Executor provider = mock(Executor.class);
        when(provider.capabilities()).thenReturn(new Executor.Capabilities(false, true, true));
        when(provider.startExecution(request))
                .thenAnswer(
                        call -> {
                            running.countDown();
                            assertTrue(finish.await(10, TimeUnit.SECONDS));
                            return new ExecutionResult(
                                    ExecutionState.SUCCEEDED,
                                    "slow",
                                    DurableJson.value("done"),
                                    null);
                        });
        when(provider.query(recovered, "original"))
                .thenReturn(new ExecutionResult(ExecutionState.RUNNING, "original", null, null));
        when(provider.cancel(recovered, "original"))
                .thenAnswer(
                        call -> {
                            cancelled.countDown();
                            return new ExecutionResult(
                                    ExecutionState.CANCELLED, "original", null, null);
                        });
        try (var records = JdbcRecordStore.sqlite(directory.resolve("lanes.db"))) {
            var store = spy(new TransactionalExecutionStore(records));
            doAnswer(
                            invocation -> {
                                invocation.callRealMethod();
                                ExecutionStore.Work work = invocation.getArgument(0);
                                if (request.equals(work.request())) renewed.countDown();
                                return null;
                            })
                    .when(store)
                    .renew(any(ExecutionStore.Work.class), any(Duration.class));
            store.admit(recovered);
            var previous = store.claim(recovered, "previous", Duration.ofSeconds(1)).orElseThrow();
            store.begin(previous);
            store.update(
                    previous, new ExecutionResult(ExecutionState.RUNNING, "original", null, null));
            store.release(previous, Duration.ZERO);
            try (var worker =
                    WorkerRuntime.builder()
                            .executionStore(store)
                            .executor("remote", provider)
                            .parallelism(1)
                            .recoveryParallelism(1)
                            .leaseDuration(Duration.ofSeconds(4))
                            .pollInterval(Duration.ofMillis(10))
                            .build()) {
                worker.localExecutor().startExecution(request);
                worker.start();
                try {
                    assertTrue(running.await(5, TimeUnit.SECONDS));
                    assertTrue(renewed.await(5, TimeUnit.SECONDS));
                    worker.localExecutor().cancel(recovered, null);
                    assertTrue(cancelled.await(5, TimeUnit.SECONDS));
                    assertFalse(worker.drain(Duration.ofMillis(400)));
                    assertFalse(worker.status().accepting());
                    assertEquals(
                            DurableException.Code.UNAVAILABLE,
                            assertThrows(
                                            DurableException.class,
                                            () -> worker.localExecutor().startExecution(request))
                                    .code());
                    assertTrue(store.claim(request, "competitor", Duration.ofSeconds(1)).isEmpty());
                } finally {
                    finish.countDown();
                }
                assertTrue(worker.drain(Duration.ofSeconds(5)));
                assertEquals(
                        ExecutionState.SUCCEEDED,
                        worker.localExecutor().query(request, null).state());
                assertEquals(
                        ExecutionState.CANCELLED,
                        worker.localExecutor().query(recovered, null).state());
                assertEquals(0, worker.status().activeStarts());
                assertEquals(0, worker.statistics(100).pending());
                verify(provider, never()).close();
            }
        }
    }

    @Test
    void capturedOutputRemainsReadableWithoutProviderAndReportsBoundedBacklog() {
        try (var records = JdbcRecordStore.sqlite(directory.resolve("output-ledger.db"))) {
            var store = new TransactionalExecutionStore(records);
            store.admit(request);
            var work = store.claim(request, "writer", Duration.ofSeconds(5)).orElseThrow();
            store.configureOutput(work, true);
            store.begin(work);
            store.update(
                    work,
                    new ExecutionResult(
                            ExecutionState.SUCCEEDED, "effect", DurableJson.value("ok"), null));
            store.appendOutput(work, new OutputPage(0, 3, "YWJj", 3, true, null));
            store.release(work, Duration.ZERO);
            var other =
                    new ExecutionRequest(
                            "other", request.thread(), request.turnId(), request.call());
            store.admit(other);
            try (var replacement =
                    new WorkerRuntime(new TransactionalExecutionStore(records), Map.of())) {
                assertEquals("YWJj", replacement.localExecutor().output(request, 0).data());
                assertTrue(replacement.localExecutor().output(request, 3).terminal());
                assertEquals(1, replacement.statistics(100).pending());
                assertEquals(2, replacement.statistics(100).pendingPublications());
                assertTrue(replacement.statistics(1).truncated());
            }
        }
    }

    @Test
    void deferredRecoverySurvivesReplacementAndCancellationBypassesBackoff() throws Exception {
        try (var records = JdbcRecordStore.sqlite(directory.resolve("backoff.db"))) {
            var store = new TransactionalExecutionStore(records);
            store.admit(request);
            var work = store.claim(request, "first", Duration.ofSeconds(5)).orElseThrow();
            store.begin(work);
            store.update(work, ExecutionResult.unknown("lost reply"));
            store.defer(work, Duration.ofSeconds(5), Duration.ofMinutes(1));
            var replacement = new TransactionalExecutionStore(records);
            assertTrue(replacement.ready(true, 10).isEmpty());
            assertTrue(replacement.claim(request, "second", Duration.ofSeconds(5)).isEmpty());
            assertEquals(1, replacement.statistics(10).unknown());
            replacement.cancel(request);
            assertEquals(1, replacement.ready(true, 10).size());
            var next = replacement.claim(request, "second", Duration.ofSeconds(5)).orElseThrow();
            replacement.defer(next, Duration.ofSeconds(5), Duration.ofMinutes(1));
            records.transaction(
                    next.key(),
                    tx -> {
                        assertEquals(2, tx.get("").path("consecutiveFailures").asInt());
                        assertTrue(tx.get("").path("nextAttemptAt").asLong() - tx.now() > 8000);
                        return null;
                    });
        }
    }

    @Test
    void aReplacementWorkerCancelsTheHandleRecoveredAfterALostStartReply() throws Exception {
        Executor provider = mock(Executor.class);
        when(provider.capabilities()).thenReturn(new Executor.Capabilities(false, true, true));
        when(provider.startExecution(request)).thenThrow(new IllegalStateException("lost reply"));
        when(provider.query(request, null))
                .thenReturn(
                        new ExecutionResult(
                                ExecutionState.RUNNING, "original-operation", null, null));
        when(provider.cancel(request, "original-operation"))
                .thenReturn(new ExecutionResult(ExecutionState.CANCELLED, null, null, null));
        try (var records = JdbcRecordStore.sqlite(directory.resolve("recovered-cancel.db"))) {
            var store = new TransactionalExecutionStore(records);
            try (var first = new WorkerRuntime(store, Map.of("remote", provider))) {
                first.localExecutor().startExecution(request);
                first.tick();
                assertEquals(
                        ExecutionState.UNKNOWN, first.localExecutor().query(request, null).state());
            }
            store.cancel(request);
            try (var replacement = new WorkerRuntime(store, Map.of("remote", provider))) {
                replacement.tick();
                assertEquals(
                        ExecutionState.CANCELLED,
                        replacement.localExecutor().query(request, null).state());
                verify(provider).cancel(request, "original-operation");
                verify(provider, times(1)).startExecution(request);
            }
        }
    }

    @Test
    void onePoolRetainsEachResolvedProvidersRecoveryGuarantees() throws Exception {
        Executor sandbox = mock(Executor.class);
        Executor mcp = mock(Executor.class);
        when(sandbox.capabilities()).thenReturn(new Executor.Capabilities(false, true, true));
        when(mcp.capabilities()).thenReturn(new Executor.Capabilities(false, false, false));
        when(sandbox.startExecution(any()))
                .thenThrow(new IllegalStateException("Lost start response"));
        when(mcp.startExecution(any())).thenThrow(new IllegalStateException("Lost start response"));
        when(sandbox.query(any(), any()))
                .thenReturn(
                        new ExecutionResult(
                                ExecutionState.SUCCEEDED,
                                "original",
                                DurableJson.value("done"),
                                null));
        var second =
                new ExecutionRequest(
                        "mcp-execution",
                        request.thread(),
                        request.turnId(),
                        new ToolCall("mcp-call", "remote", "mcp", DurableJson.value(Map.of())));
        try (var records = JdbcRecordStore.sqlite(directory.resolve("resolved.db"));
                var worker =
                        new WorkerRuntime(
                                new TransactionalExecutionStore(records),
                                call -> call.call().name().equals("mcp") ? mcp : sandbox,
                                Duration.ofSeconds(5),
                                Duration.ofMillis(1),
                                1,
                                ignored -> {})) {
            worker.localExecutor().startExecution(request);
            worker.localExecutor().startExecution(second);
            worker.tick();
            Thread.sleep(5);
            worker.tick();
            assertEquals(
                    ExecutionState.SUCCEEDED, worker.localExecutor().query(request, null).state());
            assertEquals(
                    ExecutionState.UNKNOWN, worker.localExecutor().query(second, null).state());
            verify(sandbox, times(1)).startExecution(any());
            verify(mcp, times(1)).startExecution(any());
            verify(mcp, never()).query(any(), any());
        }
    }

    @Test
    void frozenTerminalSurvivesOutputFailureAndPublishesOnlyAfterSeal() throws Exception {
        var starts = new AtomicInteger();
        var failed = new AtomicBoolean();
        Executor provider =
                new Executor() {
                    public Capabilities capabilities() {
                        return new Capabilities(false, false, false, true);
                    }

                    public ExecutionResult startExecution(ExecutionRequest request) {
                        starts.incrementAndGet();
                        return new ExecutionResult(
                                ExecutionState.SUCCEEDED,
                                "original",
                                DurableJson.value("done"),
                                null);
                    }

                    public ExecutionResult query(ExecutionRequest request, String handle) {
                        throw new AssertionError("Frozen outcome must not be queried again");
                    }

                    public ExecutionResult cancel(ExecutionRequest request, String handle) {
                        throw new AssertionError();
                    }

                    public OutputPage output(ExecutionRequest request, long offset) {
                        if (!failed.getAndSet(true))
                            throw new IllegalStateException(
                                    "Output transport failed after terminal effect");
                        return new OutputPage(0, 3, "YWJj", 3, true, null);
                    }
                };
        try (var records = JdbcRecordStore.sqlite(directory.resolve("output.db"))) {
            var store = new TransactionalExecutionStore(records);
            try (var worker =
                    new WorkerRuntime(
                            store,
                            Map.of("remote", provider),
                            Duration.ofSeconds(5),
                            Duration.ofMillis(1),
                            1,
                            ignored -> {})) {
                worker.localExecutor().startExecution(request);
                assertThrows(IllegalStateException.class, worker::tick);
                assertFalse(worker.localExecutor().query(request, null).state().terminal());
            }
            Thread.sleep(5);
            try (var worker = new WorkerRuntime(store, Map.of("remote", provider))) {
                worker.tick();
                assertEquals(
                        ExecutionState.SUCCEEDED,
                        worker.localExecutor().query(request, null).state());
                assertEquals(1, starts.get());
                var key = store.keys(null, 1).get(0);
                var events = new ArrayList<ExecutionMessages.Event>();
                while (store.pendingEvent(key).isPresent()) {
                    var event = store.pendingEvent(key).orElseThrow();
                    events.add(event);
                    store.published(event);
                }
                assertEquals(3, events.get(events.size() - 2).output().nextOffset());
                assertEquals(
                        ExecutionState.SUCCEEDED, events.get(events.size() - 1).result().state());
            }
        }
    }

    @Test
    void acknowledgementPrecedesDispatchAndReplacementQueriesOriginalOperation() throws Exception {
        Path file = directory.resolve("worker.db");
        var starts = new AtomicInteger();
        var queries = new AtomicInteger();
        Executor provider =
                new Executor() {
                    public Capabilities capabilities() {
                        return new Capabilities(false, true, true);
                    }

                    public ExecutionResult startExecution(ExecutionRequest request) {
                        starts.incrementAndGet();
                        throw new IllegalStateException("Response lost after remote acceptance");
                    }

                    public ExecutionResult query(ExecutionRequest request, String handle) {
                        queries.incrementAndGet();
                        return new ExecutionResult(
                                ExecutionState.SUCCEEDED,
                                "original",
                                DurableJson.value("output"),
                                null);
                    }

                    public ExecutionResult cancel(ExecutionRequest request, String handle) {
                        return query(request, handle);
                    }
                };
        var store = new TransactionalExecutionStore(JdbcRecordStore.sqlite(file));
        try (var worker =
                new WorkerRuntime(
                        store,
                        Map.of("remote", provider),
                        Duration.ofSeconds(5),
                        Duration.ofMillis(1),
                        1,
                        error -> {
                            throw new AssertionError(error);
                        })) {
            assertEquals(
                    ExecutionState.PENDING, worker.localExecutor().startExecution(request).state());
            assertEquals(0, starts.get());
            worker.tick();
            assertEquals(
                    ExecutionState.UNKNOWN, worker.localExecutor().query(request, null).state());
        }
        Thread.sleep(5);
        try (var worker =
                new WorkerRuntime(
                        new TransactionalExecutionStore(JdbcRecordStore.sqlite(file)),
                        Map.of("remote", provider))) {
            assertEquals(
                    ExecutionState.UNKNOWN, worker.localExecutor().startExecution(request).state());
            worker.tick();
            assertEquals(
                    ExecutionState.SUCCEEDED, worker.localExecutor().query(request, null).state());
            assertEquals(1, starts.get());
            assertEquals(1, queries.get());
            worker.localExecutor().cancel(request, null);
            assertEquals(
                    ExecutionState.SUCCEEDED,
                    worker.localExecutor()
                            .query(request, null)
                            .state()); // Completed operation wins.
            var changed =
                    new ExecutionRequest(
                            request.executionId(),
                            request.thread(),
                            request.turnId(),
                            new ToolCall("call", "remote", "job", DurableJson.value("different")));
            assertThrows(
                    DurableException.class, () -> worker.localExecutor().startExecution(changed));
        }
    }

    @Test
    void cancellationBeforeDispatchNeverCallsProvider() {
        var store =
                new TransactionalExecutionStore(
                        JdbcRecordStore.sqlite(directory.resolve("cancel.db")));
        try (var worker = new WorkerRuntime(store, Map.of())) {
            worker.localExecutor().startExecution(request);
            assertEquals(
                    ExecutionState.CANCELLED, worker.localExecutor().cancel(request, null).state());
            worker.tick();
            assertEquals(
                    ExecutionState.CANCELLED, worker.localExecutor().query(request, null).state());
        }
    }

    @Test
    void staleWorkerCannotPublishResult() throws Exception {
        var store =
                new TransactionalExecutionStore(
                        JdbcRecordStore.sqlite(directory.resolve("fence.db")));
        store.admit(request);
        var key = store.keys(null, 1).get(0);
        var old = store.claim(key, "old", Duration.ofSeconds(1)).orElseThrow();
        store.begin(old);
        store.release(old, Duration.ZERO);
        var current = store.claim(key, "new", Duration.ofSeconds(1)).orElseThrow();
        assertThrows(
                DurableException.class,
                () ->
                        store.update(
                                old,
                                new ExecutionResult(
                                        ExecutionState.SUCCEEDED,
                                        null,
                                        DurableJson.value("late"),
                                        null)));
        store.update(
                current, new ExecutionResult(ExecutionState.UNKNOWN, null, null, "Needs query"));
        assertEquals(ExecutionState.UNKNOWN, store.query(request).state());
    }

    @Test
    void missingProviderDoesNotRecordAnExternalDispatchAttempt() throws Exception {
        var store =
                new TransactionalExecutionStore(
                        JdbcRecordStore.sqlite(directory.resolve("configuration.db")));
        try (var worker =
                new WorkerRuntime(
                        store,
                        Map.of(),
                        Duration.ofSeconds(5),
                        Duration.ofMillis(1),
                        1,
                        error -> {})) {
            worker.localExecutor().startExecution(request);
            assertEquals(
                    DurableException.Code.INCOMPATIBLE,
                    assertThrows(DurableException.class, worker::tick).code());
        }
        Thread.sleep(5);
        var work =
                store.claim(store.keys(null, 1).get(0), "replacement", Duration.ofSeconds(1))
                        .orElseThrow();
        assertFalse(work.attempted());
        assertEquals(ExecutionState.PENDING, work.result().state());
    }
}
