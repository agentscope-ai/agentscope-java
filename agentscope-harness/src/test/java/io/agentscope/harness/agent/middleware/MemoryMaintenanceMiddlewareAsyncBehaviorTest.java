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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.coordination.LocalPeriodicGate;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Regression tests for the issue where {@code onAgent} used {@code concatWith} to append memory
 * maintenance onto the returned {@link Flux}, forcing callers that consume the response to
 * completion (e.g. {@code blockLast()}, {@code takeLast(1)}) to wait for the full consolidation
 * LLM call inside {@code consolidateMemory()}.
 *
 * <p>{@code onAgent} must detach maintenance so the returned Flux completes as soon as the
 * underlying agent call completes, independent of how long maintenance takes.
 *
 * <p>See <a href="https://github.com/agentscope-ai/agentscope-java/issues/2225">issue #2225</a>
 * and <a href="https://github.com/agentscope-ai/agentscope-java/issues/2276">issue #2276</a>.
 */
@Tag("unit")
class MemoryMaintenanceMiddlewareAsyncBehaviorTest {

    @BeforeEach
    void resetSharedTimerMap() {
        LocalPeriodicGate.clearForTests();
    }

    /**
     * The returned Flux must complete before a slow consolidation finishes. With
     * {@code concatWith} the Flux is held open until {@code consolidate().block()} returns,
     * inflating latency by the full model round-trip (reported as ~44s in production).
     */
    @Test
    void onAgent_completesBeforeSlowConsolidationFinishes(@TempDir Path tmp) throws Exception {
        WorkspaceManager wsm = new WorkspaceManager(tmp);

        CountDownLatch consolidationStarted = new CountDownLatch(1);
        CountDownLatch releaseConsolidation = new CountDownLatch(1);
        MemoryConsolidator consolidator = mock(MemoryConsolidator.class);
        when(consolidator.consolidate(any()))
                .thenAnswer(
                        invocation ->
                                Mono.<Void>fromRunnable(
                                        () -> {
                                            consolidationStarted.countDown();
                                            await(releaseConsolidation);
                                        }));

        MemoryMaintenanceMiddleware mw = new MemoryMaintenanceMiddleware(wsm, consolidator);
        AgentInput input = new AgentInput(List.of(userMsg("hi")));

        long start = System.nanoTime();
        List<AgentEvent> events =
                mw.onAgent(
                                (Agent) null,
                                RuntimeContext.empty(),
                                input,
                                in -> Flux.<AgentEvent>empty())
                        .collectList()
                        .block(Duration.ofSeconds(5));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(events != null && events.isEmpty());
        assertTrue(
                elapsedMs < 1000,
                () ->
                        "onAgent should complete before consolidation finishes, but took "
                                + elapsedMs
                                + "ms");
        assertTrue(
                consolidationStarted.await(2, TimeUnit.SECONDS),
                "consolidation should have started on a detached background thread");

        releaseConsolidation.countDown();
    }

    /**
     * A maintenance error must not propagate to the returned Flux. With detach, the error is
     * caught inside the background subscription and logged, never reaching the caller.
     */
    @Test
    void onAgent_maintenanceError_doesNotPropagateToFlux(@TempDir Path tmp) throws Exception {
        WorkspaceManager wsm = new WorkspaceManager(tmp);
        MemoryConsolidator consolidator = mock(MemoryConsolidator.class);
        when(consolidator.consolidate(any()))
                .thenReturn(Mono.error(new RuntimeException("consolidation LLM failed")));

        MemoryMaintenanceMiddleware mw = new MemoryMaintenanceMiddleware(wsm, consolidator);
        AgentInput input = new AgentInput(List.of(userMsg("hi")));

        List<AgentEvent> events =
                mw.onAgent(
                                (Agent) null,
                                RuntimeContext.empty(),
                                input,
                                in -> Flux.<AgentEvent>empty())
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertTrue(events != null && events.isEmpty());
    }

    @Test
    void onAgent_afterClose_doesNotScheduleNewMaintenance(@TempDir Path tmp) throws Exception {
        WorkspaceManager wsm = new WorkspaceManager(tmp);
        MemoryConsolidator consolidator = mock(MemoryConsolidator.class);
        MemoryMaintenanceMiddleware mw = new MemoryMaintenanceMiddleware(wsm, consolidator);

        // Nothing in flight, so close() returns immediately but marks the middleware closed.
        mw.close();

        AgentInput input = new AgentInput(List.of(userMsg("hi")));
        List<AgentEvent> events =
                mw.onAgent(
                                (Agent) null,
                                RuntimeContext.empty(),
                                input,
                                in -> Flux.<AgentEvent>empty())
                        .collectList()
                        .block(Duration.ofSeconds(5));
        assertTrue(events != null && events.isEmpty());

        // Poll for the background subscription to settle, then verify no consolidator
        // interaction.
        awaitPendingSettled(mw, Duration.ofSeconds(2));
        verifyNoInteractions(consolidator);
    }

    @Test
    void close_waitsForPendingMaintenance_thenReturns(@TempDir Path tmp) throws Exception {
        WorkspaceManager wsm = new WorkspaceManager(tmp);

        CountDownLatch releaseConsolidation = new CountDownLatch(1);
        AtomicBoolean consolidationCompleted = new AtomicBoolean(false);
        MemoryConsolidator consolidator = mock(MemoryConsolidator.class);
        when(consolidator.consolidate(any()))
                .thenAnswer(
                        invocation ->
                                Mono.<Void>fromRunnable(
                                        () -> {
                                            try {
                                                releaseConsolidation.await(5, TimeUnit.SECONDS);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                            consolidationCompleted.set(true);
                                        }));

        MemoryMaintenanceMiddleware mw = new MemoryMaintenanceMiddleware(wsm, consolidator);
        AgentInput input = new AgentInput(List.of(userMsg("hi")));

        // Trigger maintenance (detached, running in background)
        mw.onAgent((Agent) null, RuntimeContext.empty(), input, in -> Flux.<AgentEvent>empty())
                .collectList()
                .block(Duration.ofSeconds(5));

        // Release consolidation so close() can drain it
        releaseConsolidation.countDown();

        long start = System.nanoTime();
        mw.close();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(
                elapsedMs < 3000,
                "close() should return promptly after maintenance completes, took "
                        + elapsedMs
                        + "ms");
        assertTrue(
                consolidationCompleted.get(),
                "consolidation should have completed before close() returned");
    }

    /**
     * When consolidation hangs indefinitely, close() must still return within
     * CLOSE_AWAIT_TIMEOUT by disposing the outstanding subscription. This verifies the
     * timeout+dispose safety net works without waiting the full 5-minute
     * CONSOLIDATION_TIMEOUT.
     */
    @Test
    void close_disposesHungConsolidation_andReturnsPromptly(@TempDir Path tmp) throws Exception {
        WorkspaceManager wsm = new WorkspaceManager(tmp);
        MemoryConsolidator consolidator = mock(MemoryConsolidator.class);
        when(consolidator.consolidate(any())).thenReturn(Mono.never());

        MemoryMaintenanceMiddleware mw = new MemoryMaintenanceMiddleware(wsm, consolidator);
        AgentInput input = new AgentInput(List.of(userMsg("hi")));

        // Trigger maintenance that will hang (consolidation never returns)
        mw.onAgent((Agent) null, RuntimeContext.empty(), input, in -> Flux.<AgentEvent>empty())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertTrue(mw.hasPendingMaintenance(), "maintenance should be pending (hanging)");

        // close() should dispose the hung maintenance and return within the await timeout (5s)
        long start = System.nanoTime();
        mw.close();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(
                elapsedMs < 6000,
                "close() should return within CLOSE_AWAIT_TIMEOUT even with hung consolidation,"
                        + " took "
                        + elapsedMs
                        + "ms");
        assertFalse(
                mw.hasPendingMaintenance(), "no pending maintenance should remain after close()");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Polls until the middleware has no pending background subscriptions. Throws
     * AssertionError if the timeout expires with pending work still in flight, so a
     * slow CI cannot silently mask a scheduling bug.
     */
    private static void awaitPendingSettled(MemoryMaintenanceMiddleware mw, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!mw.hasPendingMaintenance()) {
                return;
            }
            Thread.sleep(10);
        }
        assertFalse(mw.hasPendingMaintenance(), "pending maintenance should have settled");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }
}
