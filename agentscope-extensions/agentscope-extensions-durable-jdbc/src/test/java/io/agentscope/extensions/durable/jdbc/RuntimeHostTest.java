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

package io.agentscope.extensions.durable.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableProtocol;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.Commit;
import io.agentscope.durable.DurableTypes.HarnessSelector;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.SessionSpec;
import io.agentscope.durable.DurableTypes.ThreadBinding;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ThreadSpec;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.HarnessAdapter;
import io.agentscope.durable.HarnessContext;
import io.agentscope.durable.TransactionalSessionBackend;
import io.agentscope.durable.TurnSchedulingStatistics.Source;
import io.agentscope.durable.Wakeups;
import io.agentscope.durable.store.RecordObjectStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeHostTest {
    @TempDir Path directory;

    @Test
    void idleDatabaseDiscoveryReportsAnEmptyScan() throws Exception {
        var adapter = mock(HarnessAdapter.class);
        when(adapter.capabilities())
                .thenReturn(
                        new HarnessAdapter.Capabilities(
                                "1",
                                HarnessAdapter.RecoveryBoundary.CHECKPOINT,
                                HarnessAdapter.EventDelivery.COMPLETED,
                                false,
                                List.of("text")));
        try (var backend = LocalBackend.open(directory);
                var runtime =
                        DurableRuntime.builder()
                                .backend(backend)
                                .harness("agent", "v1", adapter)
                                .pollInterval(Duration.ofMinutes(1))
                                .build()) {
            runtime.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!runtime.schedulingStatistics().scans().containsKey(Source.DATABASE)
                    && System.nanoTime() < deadline) Thread.sleep(10);
            var scan = runtime.schedulingStatistics().scans().get(Source.DATABASE);
            assertTrue(scan != null, "the initial database scan did not run");
            assertEquals(1, scan.total());
            assertEquals(1, scan.empty());
            assertEquals(0, scan.failures());
            assertEquals(1.0, scan.emptyRatio());
        }
    }

    @Test
    void incompatibleHostLeavesTheRunAvailableToACompatibleReplacement() {
        var adapter = mock(HarnessAdapter.class);
        when(adapter.capabilities())
                .thenReturn(
                        new HarnessAdapter.Capabilities(
                                "1",
                                HarnessAdapter.RecoveryBoundary.CHECKPOINT,
                                HarnessAdapter.EventDelivery.COMPLETED,
                                false,
                                List.of("text")));
        try (var backend = LocalBackend.open(directory);
                var runtime =
                        DurableRuntime.builder()
                                .backend(backend)
                                .harness("agent", "v2", adapter)
                                .onError((key, error) -> {})
                                .build()) {
            var key = new SessionKey("scope", "older");
            backend.create(
                    new SessionSpec(
                            key, new ThreadBinding("agent", "v1", "1"), DurableJson.value(null)));
            var turn = runtime.getSession(key).submit("input", "hello");
            runtime.tick(
                    new ThreadKey(
                            key, "main")); // A stale notification can reach a host with a different
            // definition.
            assertEquals(
                    DurableException.Code.INCOMPATIBLE,
                    ((DurableException) runtime.lastFailure(new ThreadKey(key, "main"))).code());
            assertEquals(
                    TurnStatus.RUNNING,
                    backend.turn(new ThreadKey(key, "main"), turn.turnId()).status());
            assertEquals(null, backend.turn(new ThreadKey(key, "main"), turn.turnId()).waiting());
        }
    }

    @Test
    void staticHostsDiscoverTheirVersionDespiteABacklogOfOtherDefinitions() throws Exception {
        var finished = new CountDownLatch(1);
        var adapter =
                new HarnessAdapter() {
                    public Capabilities capabilities() {
                        return new Capabilities(
                                "1",
                                RecoveryBoundary.CHECKPOINT,
                                EventDelivery.COMPLETED,
                                false,
                                List.of("text"));
                    }

                    public void execute(HarnessContext context) {
                        finish(context, "done");
                        finished.countDown();
                    }
                };
        try (var backend = LocalBackend.open(directory);
                var runtime =
                        DurableRuntime.builder()
                                .backend(backend)
                                .harness("agent", "v2", adapter)
                                .parallelism(1)
                                .pollInterval(Duration.ofMillis(20))
                                .build()) {
            for (int i = 0; i < 20; i++) {
                var other = new SessionKey("scope", "old-" + i);
                backend.create(
                        new SessionSpec(
                                other,
                                new ThreadBinding("agent", "v1", "1"),
                                DurableJson.value(null)));
                backend.submit(
                        new ThreadKey(other, "main"), "input", DurableJson.value("older work"));
            }
            var key = new SessionKey("scope", "current");
            runtime.createSession(key, "agent").mainThread().view().definition();
            var turn = runtime.getSession(key).submit("input", "hello");
            runtime.start();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(
                    TurnStatus.SUCCEEDED,
                    runtime.getSession(key).getTurn(turn.turnId()).status().status());
            var older = new SessionKey("scope", "old-0");
            assertEquals(TurnStatus.QUEUED, backend.snapshot(older).turns().get(0).status());
        }
    }

    @Test
    void sessionWakeupKeepsWorkArrivingDuringAnActiveInvocationAndLeavesOtherSessionsAlone()
            throws Exception {
        var firstEntered = new CountDownLatch(1);
        var finishFirst = new CountDownLatch(1);
        var firstCommitted = new CountDownLatch(1);
        var returnFirst = new CountDownLatch(1);
        var secondFinished = new CountDownLatch(1);
        var ours = new SessionKey("scope", "current");
        var other = new SessionKey("scope", "other");
        var adapter =
                new HarnessAdapter() {
                    public Capabilities capabilities() {
                        return new Capabilities(
                                "1",
                                RecoveryBoundary.CHECKPOINT,
                                EventDelivery.COMPLETED,
                                false,
                                List.of("text"));
                    }

                    public void execute(HarnessContext context) throws Exception {
                        if (firstEntered.getCount() != 0) {
                            firstEntered.countDown();
                            assertTrue(finishFirst.await(5, TimeUnit.SECONDS));
                            finish(context, "first");
                            firstCommitted.countDown();
                            assertTrue(returnFirst.await(5, TimeUnit.SECONDS));
                        } else {
                            assertEquals(ours, context.lease().session());
                            finish(context, "second");
                            secondFinished.countDown();
                        }
                    }
                };
        try (var records = JdbcRecordStore.sqlite(directory.resolve("session-wakeup.db"));
                var storage = new TransactionalSessionBackend(records);
                var backend = spy(storage.withWakeups(Wakeups.local()));
                var runtime =
                        DurableRuntime.builder()
                                .backend(backend)
                                .harness("agent", "v1", adapter)
                                .parallelism(1)
                                .pollInterval(Duration.ofMinutes(1))
                                .build()) {
            var thread = runtime.createSession(ours, "agent").mainThread();
            var observer = runtime.createSession(other, "agent").mainThread();
            thread.submit("first", "first");
            runtime.start();
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            var unrelated = storage.submit(observer.key(), "other", DurableJson.value("other"));
            var queued = thread.submit("second", "second");
            finishFirst.countDown();
            assertTrue(firstCommitted.await(5, TimeUnit.SECONDS));
            runtime.wake(ours);
            returnFirst.countDown();
            assertTrue(secondFinished.await(5, TimeUnit.SECONDS));
            verify(backend).ready(anyList(), anyInt());
            assertFalse(backend.capabilities().messageDrivenScheduling());
            assertEquals(TurnStatus.SUCCEEDED, queued.status().status());
            assertEquals(
                    TurnStatus.QUEUED, storage.turn(observer.key(), unrelated.turnId()).status());
        }
    }

    @Test
    void awaitReadsOnTheMatchingNotificationWithASeparateDeadline() throws Exception {
        try (var backend = spy(LocalBackend.open(directory));
                var runtime =
                        DurableRuntime.builder()
                                .backend(backend)
                                .definition("agent", "v1", "1")
                                .observationInterval(Duration.ofMinutes(1))
                                .build()) {
            var thread =
                    runtime.createSession(new SessionKey("scope", "observed"), "agent")
                            .mainThread();
            var turn = thread.submit("first", "one");
            var lease = backend.claim(thread.key(), "runner", Duration.ofMinutes(1)).orElseThrow();
            var observation = turn.await(Duration.ofSeconds(5)).toCompletableFuture();
            verify(backend, timeout(1000)).turn(thread.key(), turn.turnId());
            finish(new HarnessContext(backend, lease, new AtomicBoolean()), "done");
            assertEquals(TurnStatus.SUCCEEDED, observation.get(2, TimeUnit.SECONDS).status());
            var queued = thread.submit("second", "two");
            var failure =
                    assertThrows(
                            ExecutionException.class,
                            () ->
                                    queued.await(Duration.ofMillis(100))
                                            .toCompletableFuture()
                                            .get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof TimeoutException);
        }
    }

    @Test
    void capabilityQueriesResolvePinnedDefinitionsWithoutClaimingOrRunningThem() throws Exception {
        var checkpoint = mock(HarnessAdapter.class);
        var tools = mock(HarnessAdapter.class);
        when(checkpoint.capabilities())
                .thenReturn(
                        new HarnessAdapter.Capabilities(
                                "state-1",
                                HarnessAdapter.RecoveryBoundary.CHECKPOINT,
                                HarnessAdapter.EventDelivery.COMPLETED,
                                false,
                                List.of("text")));
        when(tools.capabilities())
                .thenReturn(
                        new HarnessAdapter.Capabilities(
                                "state-2",
                                HarnessAdapter.RecoveryBoundary.TOOL_BOUNDARY,
                                HarnessAdapter.EventDelivery.DELTA,
                                true,
                                List.of("text", "image")));
        try (var backend = LocalBackend.open(directory);
                var runtime =
                        DurableRuntime.builder()
                                .backend(backend)
                                .harness("static", "v1", checkpoint)
                                .harness("static", "v2", tools)
                                .resolver(
                                        "dynamic",
                                        definition ->
                                                switch (definition.definitionVersion()) {
                                                    case "v1" -> checkpoint;
                                                    case "v2" -> tools;
                                                    default -> null;
                                                })
                                .build()) {
            var key = new SessionKey("scope", "capabilities");
            var definition =
                    new ThreadSpec(
                            new ThreadKey(key, "main"),
                            new ThreadBinding("dynamic", "v1", "state-1"));
            backend.create(
                    new SessionSpec(
                            definition.key().session(),
                            definition.binding(),
                            DurableJson.value(null)));
            var turn =
                    backend.submit(
                            new ThreadKey(key, "main"), "request", DurableJson.value("input"));
            var available = runtime.executionCapabilities();
            assertEquals(List.of("dynamic"), available.resolvedHarnesses());
            DurableProtocol.validate("runtimeCapabilities", available);
            var actual = runtime.capabilities(definition);
            DurableProtocol.validate("definitionCapabilities", actual);
            assertEquals(checkpoint.capabilities(), actual.harness());
            assertEquals(
                    tools.capabilities(),
                    runtime.capabilities(
                                    new ThreadSpec(
                                            new ThreadKey(key, "main"),
                                            new ThreadBinding("dynamic", "v2", "state-2")))
                            .harness());
            assertEquals(
                    checkpoint.capabilities(),
                    runtime.capabilities(
                                    new ThreadSpec(
                                            new ThreadKey(key, "main"),
                                            new ThreadBinding("static", "v1", "state-1")))
                            .harness());
            assertEquals(
                    TurnStatus.QUEUED,
                    backend.turn(new ThreadKey(key, "main"), turn.turnId()).status());
            assertEquals(
                    DurableException.Code.INCOMPATIBLE,
                    assertThrows(
                                    DurableException.class,
                                    () ->
                                            runtime.capabilities(
                                                    new ThreadSpec(
                                                            new ThreadKey(key, "main"),
                                                            new ThreadBinding(
                                                                    "dynamic", "v3", "state-1"))))
                            .code());
            assertEquals(
                    DurableException.Code.INCOMPATIBLE,
                    assertThrows(
                                    DurableException.class,
                                    () ->
                                            runtime.capabilities(
                                                    new ThreadSpec(
                                                            new ThreadKey(key, "main"),
                                                            new ThreadBinding(
                                                                    "static", "v1", "state-2"))))
                            .code());
            verify(checkpoint, never()).execute(any());
            verify(tools, never()).execute(any());
        }
    }

    @Test
    void boundedAdmissionAndDrainLeaveQueuedWorkRecoverable() throws Exception {
        try (var backend = LocalBackend.open(directory)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var adapter =
                    new HarnessAdapter() {
                        public Capabilities capabilities() {
                            return new Capabilities(
                                    "1",
                                    RecoveryBoundary.CHECKPOINT,
                                    EventDelivery.COMPLETED,
                                    false,
                                    List.of("text"));
                        }

                        public void execute(HarnessContext context) throws Exception {
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS))
                                throw new IllegalStateException("test release missing");
                            finish(context, "done");
                        }
                    };
            try (var runtime =
                    DurableRuntime.builder()
                            .backend(backend)
                            .harness("agent", "v1", adapter)
                            .parallelism(1)
                            .build()) {
                var a = new SessionKey("scope", "a");
                var b = new SessionKey("scope", "b");
                runtime.createSession(a, "agent").mainThread().view().definition();
                runtime.createSession(b, "agent").mainThread().view().definition();
                var first = runtime.getSession(a).submit("one", "input");
                var second = runtime.getSession(b).submit("two", "input");
                assertTrue(runtime.offer(new ThreadKey(a, "main")));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(runtime.offer(new ThreadKey(a, "main")));
                assertFalse(runtime.offer(new ThreadKey(b, "main")));
                assertFalse(runtime.drain(Duration.ofMillis(10)));
                release.countDown();
                assertTrue(runtime.drain(Duration.ofSeconds(5)));
                assertFalse(runtime.offer(new ThreadKey(b, "main")));
                runtime.tick(new ThreadKey(b, "main"));
                assertEquals(
                        TurnStatus.SUCCEEDED,
                        runtime.getSession(a).getTurn(first.turnId()).status().status());
                assertEquals(
                        TurnStatus.QUEUED,
                        runtime.getSession(b).getTurn(second.turnId()).status().status());
                assertEquals(
                        List.of(new ThreadKey(b, "main")),
                        backend.ready(List.of(HarnessSelector.all("agent")), 10).stream()
                                .map(ThreadSpec::key)
                                .toList());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void drainingReleasesAnUnsettledTurnWithoutTheNormalReleaseDelay() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var adapter = mock(HarnessAdapter.class);
        when(adapter.capabilities())
                .thenReturn(
                        new HarnessAdapter.Capabilities(
                                "1",
                                HarnessAdapter.RecoveryBoundary.CHECKPOINT,
                                HarnessAdapter.EventDelivery.COMPLETED,
                                false,
                                List.of("text")));
        doAnswer(
                        call -> {
                            entered.countDown();
                            assertTrue(finish.await(5, TimeUnit.SECONDS));
                            return null;
                        })
                .when(adapter)
                .execute(any());
        try (var backend = LocalBackend.open(directory);
                var runtime =
                        DurableRuntime.builder()
                                .backend(backend)
                                .harness("agent", "v1", adapter)
                                .releaseDelay(Duration.ofMinutes(2))
                                .build()) {
            var thread =
                    runtime.createSession(new SessionKey("scope", "drain"), "agent").mainThread();
            var turn = thread.submit("one", "input");
            assertTrue(runtime.offer(thread.key()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(runtime.drain(Duration.ZERO));
            finish.countDown();
            assertTrue(runtime.drain(Duration.ofSeconds(5)));
            var replacement =
                    backend.claim(thread.key(), "replacement", Duration.ofSeconds(30))
                            .orElseThrow();
            assertEquals(turn.turnId(), replacement.turnId());
        } finally {
            finish.countDown();
        }
    }

    @Test
    void resolverUsesEachSessionsFrozenVersion() {
        try (var backend = LocalBackend.open(directory)) {
            var calls = new AtomicInteger();
            try (var runtime =
                    DurableRuntime.builder()
                            .backend(backend)
                            .resolver(
                                    "agentscope",
                                    definition ->
                                            new HarnessAdapter() {
                                                public Capabilities capabilities() {
                                                    return new Capabilities(
                                                            "1",
                                                            RecoveryBoundary.CHECKPOINT,
                                                            EventDelivery.COMPLETED,
                                                            false,
                                                            List.of("text"));
                                                }

                                                public void execute(HarnessContext context) {
                                                    calls.incrementAndGet();
                                                    finish(context, definition.definitionVersion());
                                                }
                                            })
                            .build()) {
                for (var version : List.of("agent-a-v1", "agent-b-v7")) {
                    var key = new SessionKey("scope", version);
                    backend.create(
                            new SessionSpec(
                                    key,
                                    new ThreadBinding("agentscope", version, "1"),
                                    DurableJson.value(null)));
                    var turn = runtime.getSession(key).submit("input", "hello");
                    runtime.tick(new ThreadKey(key, "main"));
                    assertEquals(
                            version,
                            runtime.getSession(key)
                                    .getTurn(turn.turnId())
                                    .status()
                                    .output()
                                    .asText());
                }
                assertEquals(2, calls.get());
            }
        }
    }

    @Test
    void transactionalPolicyDenialRollsBackReservationAndNativeState() {
        try (var records = JdbcRecordStore.sqlite(directory.resolve("policy.db"))) {
            var backend =
                    new TransactionalSessionBackend(
                            records,
                            new RecordObjectStore(records),
                            (tx, definition, lease, commit) -> {
                                tx.put("platform-reservation", DurableJson.value("reserved"));
                                throw new DurableException(
                                        DurableException.Code.CONFLICT, "Permission revoked");
                            });
            var key = new SessionKey("scope", "policy");
            backend.create(
                    new SessionSpec(
                            key, new ThreadBinding("agent", "v1", "1"), DurableJson.value(null)));
            backend.submit(new ThreadKey(key, "main"), "input", DurableJson.value("hello"));
            var lease =
                    backend.claim(new ThreadKey(key, "main"), "owner", Duration.ofSeconds(30))
                            .orElseThrow();
            assertThrows(
                    DurableException.class,
                    () ->
                            backend.commit(
                                    lease,
                                    new Commit(
                                            "denied",
                                            0,
                                            DurableJson.value("new state"),
                                            List.of(),
                                            List.of(),
                                            List.of(),
                                            List.of(),
                                            TurnStatus.SUCCEEDED,
                                            null,
                                            null,
                                            null)));
            assertTrue(backend.frame(lease).checkpoint().isNull());
            assertTrue(
                    backend.receipt(new ThreadKey(key, "main"), lease.turnId(), "denied")
                            .isEmpty());
            records.transaction(
                    key.storageKey(),
                    tx -> {
                        assertEquals(null, tx.get("platform-reservation"));
                        return null;
                    });
        }
    }

    private static void finish(HarnessContext context, String output) {
        var frame = context.frame();
        context.commit(
                new Commit(
                        "finish",
                        frame.turn().revision(),
                        DurableJson.value(output),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        TurnStatus.SUCCEEDED,
                        null,
                        DurableJson.value(output),
                        null));
    }
}
