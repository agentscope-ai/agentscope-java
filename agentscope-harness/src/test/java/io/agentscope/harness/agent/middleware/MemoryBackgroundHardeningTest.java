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
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.coordination.LocalPeriodicGate;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Hardening tests for the fire-and-forget memory background work introduced with the detach
 * (#2777): a hung model call must not pin the conversation's coalescing slot (or a
 * boundedElastic worker) forever. Cancellation belongs to the per-pipeline timeouts only —
 * {@link MemoryBackgroundTasks#awaitQuiescence} deliberately abandons in-flight work when its
 * budget elapses so a close during a healthy flush never drops the last turn's memory
 * extraction.
 */
@Tag("unit")
class MemoryBackgroundHardeningTest {

    @BeforeEach
    void resetSharedTimerMap() {
        LocalPeriodicGate.clearForTests();
    }

    /** Polls until quiescence is reached within the given budget; fails the test on timeout. */
    private static boolean pollQuiescence(Duration budget) throws InterruptedException {
        long deadline = System.nanoTime() + budget.toNanos();
        while (System.nanoTime() < deadline) {
            if (MemoryBackgroundTasks.awaitQuiescence(500, TimeUnit.MILLISECONDS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * N1 invariant: quiescence waiting must <em>abandon</em>, not cancel. A close() during an
     * in-flight flush is the common case with fire-and-forget memory work, and the last turn's
     * extraction must keep running and complete after the caller's budget elapses — genuinely
     * hung work is the pipeline timeout's job, not the quiescence sweep's.
     */
    @Test
    void awaitQuiescence_timeout_doesNotCancelHealthyFlush(@TempDir Path tmp) throws Exception {
        WorkspaceManager wsm = new WorkspaceManager(tmp);
        CountDownLatch flushStarted = new CountDownLatch(1);
        CountDownLatch releaseFlush = new CountDownLatch(1);
        AtomicBoolean flushCancelled = new AtomicBoolean(false);
        Model slowModel = mock(Model.class);
        when(slowModel.stream(any(), any(), any()))
                .thenAnswer(
                        inv ->
                                Flux.<ChatResponse>create(
                                                sink -> {
                                                    flushStarted.countDown();
                                                    try {
                                                        releaseFlush.await(10, TimeUnit.SECONDS);
                                                    } catch (InterruptedException e) {
                                                        Thread.currentThread().interrupt();
                                                    }
                                                    sink.complete();
                                                })
                                        .doOnCancel(() -> flushCancelled.set(true)));

        MemoryFlushMiddleware mw =
                new MemoryFlushMiddleware(
                        wsm,
                        slowModel,
                        MemoryFlushManager.DEFAULT_FLUSH_PROMPT,
                        MemoryConfig.FlushTrigger.always());
        mw.setFlushTimeoutForTests(Duration.ofMinutes(5));

        RuntimeContext rc = RuntimeContext.builder().sessionId("s1").userId("u1").build();
        rc.setAgentState(stateWithMessages(userMsg("hello")));
        AgentInput input = new AgentInput(List.of(userMsg("hello")));

        mw.onAgent((Agent) null, rc, input, in -> Flux.<AgentEvent>empty())
                .collectList()
                .block(Duration.ofSeconds(5));
        assertTrue(flushStarted.await(2, TimeUnit.SECONDS), "flush should be in flight");

        // A short quiescence budget (like HarnessAgent.close()'s 5s) must give up waiting...
        assertFalse(
                MemoryBackgroundTasks.awaitQuiescence(150, TimeUnit.MILLISECONDS),
                "quiescence must report failure while the flush is still running");

        // ...but the flush itself must survive the abandoned wait and complete afterwards.
        releaseFlush.countDown();
        assertTrue(
                pollQuiescence(Duration.ofSeconds(5)),
                "the abandoned flush must complete on its own");
        assertFalse(
                flushCancelled.get(),
                "quiescence must never cancel healthy in-flight work (final-memory loss)");
        wsm.close();
    }

    @Test
    void hungFlush_timesOut_releasesQuiescenceAndQueueRecovers(@TempDir Path tmp) throws Exception {
        WorkspaceManager wsm = new WorkspaceManager(tmp);
        AtomicInteger modelCalls = new AtomicInteger();
        Model hungModel = mock(Model.class);
        when(hungModel.stream(any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            modelCalls.incrementAndGet();
                            return Flux.<ChatResponse>never();
                        });

        MemoryFlushMiddleware mw =
                new MemoryFlushMiddleware(
                        wsm,
                        hungModel,
                        MemoryFlushManager.DEFAULT_FLUSH_PROMPT,
                        MemoryConfig.FlushTrigger.always());
        mw.setFlushTimeoutForTests(Duration.ofMillis(200));

        RuntimeContext rc = RuntimeContext.builder().sessionId("s1").userId("u1").build();
        rc.setAgentState(stateWithMessages(userMsg("hello")));
        AgentInput input = new AgentInput(List.of(userMsg("hello")));

        mw.onAgent((Agent) null, rc, input, in -> Flux.<AgentEvent>empty())
                .collectList()
                .block(Duration.ofSeconds(5));

        // Without the timeout, a hung model pins the in-flight counter forever and every
        // later awaitQuiescence call (e.g. HarnessAgent.close()) waits its full budget, gives
        // up, and leaves the task running.
        assertTrue(
                pollQuiescence(Duration.ofSeconds(5)),
                "flush timeout must release the in-flight slot");

        // Queue recovery: after the hung flush timed out, a fresh call for the same
        // conversation must reach the model again instead of queueing behind the dead slot.
        mw.onAgent((Agent) null, rc, input, in -> Flux.<AgentEvent>empty())
                .collectList()
                .block(Duration.ofSeconds(5));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (modelCalls.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(modelCalls.get() >= 2, "queue must recover after the flush timeout");

        assertTrue(pollQuiescence(Duration.ofSeconds(5)));
        wsm.close();
    }

    @Test
    void hungMaintenance_timesOut_releasesQuiescence(@TempDir Path tmp) throws Exception {
        WorkspaceManager wsm = new WorkspaceManager(tmp);
        AtomicBoolean consolidationCancelled = new AtomicBoolean(false);
        MemoryConsolidator consolidator = mock(MemoryConsolidator.class);
        when(consolidator.consolidate(any()))
                .thenAnswer(
                        inv ->
                                Mono.<Void>never()
                                        .doOnCancel(() -> consolidationCancelled.set(true)));

        MemoryMaintenanceMiddleware mw = new MemoryMaintenanceMiddleware(wsm, consolidator);
        mw.setMaintenanceTimeoutForTests(Duration.ofMillis(200));
        AgentInput input = new AgentInput(List.of(userMsg("hi")));

        mw.onAgent((Agent) null, RuntimeContext.empty(), input, in -> Flux.<AgentEvent>empty())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertTrue(
                pollQuiescence(Duration.ofSeconds(5)),
                "maintenance timeout must release the in-flight slot");
        assertTrue(
                consolidationCancelled.get(),
                "the timeout must cancel the consolidation subscription itself, freeing the"
                        + " worker — not just the in-flight counter (the old .block() variant"
                        + " released the counter while the worker stayed stuck)");
        wsm.close();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static AgentState stateWithMessages(Msg... msgs) {
        AgentState.Builder b = AgentState.builder().sessionId("s1");
        for (Msg m : msgs) {
            b.addMessage(m);
        }
        return b.build();
    }
}
