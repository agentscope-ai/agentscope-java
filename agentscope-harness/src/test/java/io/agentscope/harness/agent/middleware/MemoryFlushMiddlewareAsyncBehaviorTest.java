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
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.coordination.LocalPeriodicGate;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
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

/**
 * Regression tests for the issue where {@code onAgent} used {@code concatWith} to append
 * memory flush onto the returned {@link Flux}, forcing callers that consume the response to
 * completion (e.g. {@code blockLast()}, {@code takeLast(1)}) to wait for the full flush LLM
 * call duration.
 *
 * <p>{@code onAgent} must detach flush so the returned Flux completes as soon as the
 * underlying agent call completes, independent of how long the flush LLM takes.
 *
 * <p>See <a href="https://github.com/agentscope-ai/agentscope-java/issues/2276">issue #2276</a>
 * and <a href="https://github.com/agentscope-ai/agentscope-java/issues/2225">issue #2225</a>.
 */
@Tag("unit")
class MemoryFlushMiddlewareAsyncBehaviorTest {

    @BeforeEach
    void resetSharedTimerMap() {
        LocalPeriodicGate.clearForTests();
    }

    /**
     * The returned Flux must complete before a slow flush LLM finishes. With {@code concatWith}
     * the Flux is held open until the LLM call completes, inflating latency by the full model
     * round-trip (reported as 19-27s per call in production).
     */
    @Test
    void onAgent_completesBeforeSlowFlushFinishes(@TempDir Path tmp) throws Exception {
        WorkspaceManager wsm = new WorkspaceManager(tmp);

        CountDownLatch flushStarted = new CountDownLatch(1);
        CountDownLatch releaseFlush = new CountDownLatch(1);
        Model slowModel = mock(Model.class);
        when(slowModel.stream(any(), any(), any()))
                .thenAnswer(
                        inv ->
                                Flux.<ChatResponse>create(
                                        sink -> {
                                            flushStarted.countDown();
                                            try {
                                                releaseFlush.await(5, TimeUnit.SECONDS);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                            sink.complete();
                                        }));

        MemoryFlushMiddleware mw =
                new MemoryFlushMiddleware(
                        wsm,
                        slowModel,
                        MemoryFlushManager.DEFAULT_FLUSH_PROMPT,
                        MemoryConfig.FlushTrigger.always());

        RuntimeContext rc = RuntimeContext.builder().sessionId("s1").userId("u1").build();
        rc.setAgentState(stateWithMessages(userMsg("hello")));

        AgentInput input = new AgentInput(List.of(userMsg("hello")));

        long start = System.nanoTime();
        List<AgentEvent> events =
                mw.onAgent((Agent) null, rc, input, in -> Flux.<AgentEvent>empty())
                        .collectList()
                        .block(Duration.ofSeconds(5));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(events != null && events.isEmpty());
        assertTrue(
                elapsedMs < 1000,
                () ->
                        "onAgent should complete before flush finishes, but took "
                                + elapsedMs
                                + "ms");
        assertTrue(
                flushStarted.await(2, TimeUnit.SECONDS),
                "flush should have started on a detached background thread");

        releaseFlush.countDown();
    }

    /**
     * A flush error must not propagate to the returned Flux. With detach, the error is caught
     * inside the background subscription and logged, never reaching the caller.
     */
    @Test
    void onAgent_flushError_doesNotPropagateToFlux(@TempDir Path tmp) throws Exception {
        WorkspaceManager wsm = new WorkspaceManager(tmp);
        Model errorModel = mock(Model.class);
        when(errorModel.stream(any(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("flush LLM failed")));

        MemoryFlushMiddleware mw =
                new MemoryFlushMiddleware(
                        wsm,
                        errorModel,
                        MemoryFlushManager.DEFAULT_FLUSH_PROMPT,
                        MemoryConfig.FlushTrigger.always());

        RuntimeContext rc = RuntimeContext.builder().sessionId("s1").userId("u1").build();
        rc.setAgentState(stateWithMessages(userMsg("hello")));

        AgentInput input = new AgentInput(List.of(userMsg("hello")));

        // Should complete normally, not error
        List<AgentEvent> events =
                mw.onAgent((Agent) null, rc, input, in -> Flux.<AgentEvent>empty())
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertTrue(events != null && events.isEmpty());
    }

    @Test
    void onAgent_noMessages_completesImmediately(@TempDir Path tmp) throws Exception {
        WorkspaceManager wsm = new WorkspaceManager(tmp);
        Model model = mock(Model.class);

        MemoryFlushMiddleware mw =
                new MemoryFlushMiddleware(
                        wsm,
                        model,
                        MemoryFlushManager.DEFAULT_FLUSH_PROMPT,
                        MemoryConfig.FlushTrigger.always());

        RuntimeContext rc = RuntimeContext.builder().sessionId("s1").userId("u1").build();
        rc.setAgentState(AgentState.builder().sessionId("s1").build());

        AgentInput input = new AgentInput(List.of());

        long start = System.nanoTime();
        List<AgentEvent> events =
                mw.onAgent((Agent) null, rc, input, in -> Flux.<AgentEvent>empty())
                        .collectList()
                        .block(Duration.ofSeconds(5));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(events != null && events.isEmpty());
        assertTrue(elapsedMs < 500, "should complete immediately with no messages to flush");
    }

    @Test
    void onAgent_afterClose_doesNotScheduleNewFlush(@TempDir Path tmp) throws Exception {
        WorkspaceManager wsm = new WorkspaceManager(tmp);
        Model model = mock(Model.class);
        when(model.stream(any(), any(), any())).thenReturn(Flux.empty());

        MemoryFlushMiddleware mw =
                new MemoryFlushMiddleware(
                        wsm,
                        model,
                        MemoryFlushManager.DEFAULT_FLUSH_PROMPT,
                        MemoryConfig.FlushTrigger.always());

        // close() with nothing in flight returns immediately but marks the middleware closed.
        mw.close();

        RuntimeContext rc = RuntimeContext.builder().sessionId("s1").userId("u1").build();
        rc.setAgentState(stateWithMessages(userMsg("hello")));
        AgentInput input = new AgentInput(List.of(userMsg("hello")));

        List<AgentEvent> events =
                mw.onAgent((Agent) null, rc, input, in -> Flux.<AgentEvent>empty())
                        .collectList()
                        .block(Duration.ofSeconds(5));
        assertTrue(events != null && events.isEmpty());

        // Poll for the background subscription to settle, then verify no model interaction.
        awaitPendingSettled(mw, Duration.ofSeconds(2));
        verifyNoInteractions(model);
    }

    @Test
    void close_waitsForPendingFlush_thenReturns(@TempDir Path tmp) throws Exception {
        WorkspaceManager wsm = new WorkspaceManager(tmp);

        CountDownLatch releaseFlush = new CountDownLatch(1);
        AtomicBoolean flushCompleted = new AtomicBoolean(false);
        Model slowModel = mock(Model.class);
        when(slowModel.stream(any(), any(), any()))
                .thenAnswer(
                        inv ->
                                Flux.<ChatResponse>create(
                                        sink -> {
                                            try {
                                                releaseFlush.await(5, TimeUnit.SECONDS);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                            flushCompleted.set(true);
                                            sink.complete();
                                        }));

        MemoryFlushMiddleware mw =
                new MemoryFlushMiddleware(
                        wsm,
                        slowModel,
                        MemoryFlushManager.DEFAULT_FLUSH_PROMPT,
                        MemoryConfig.FlushTrigger.always());

        RuntimeContext rc = RuntimeContext.builder().sessionId("s1").userId("u1").build();
        rc.setAgentState(stateWithMessages(userMsg("hello")));
        AgentInput input = new AgentInput(List.of(userMsg("hello")));

        // Trigger a flush (detached, running in background)
        mw.onAgent((Agent) null, rc, input, in -> Flux.<AgentEvent>empty())
                .collectList()
                .block(Duration.ofSeconds(5));

        // Release the flush so close() can drain it
        releaseFlush.countDown();

        // close() should return within the await timeout (flush completes quickly after release)
        long start = System.nanoTime();
        mw.close();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(
                elapsedMs < 3000,
                "close() should return promptly after flush completes, took " + elapsedMs + "ms");
        assertTrue(flushCompleted.get(), "flush should have completed before close() returned");
    }

    /**
     * When the flush LLM hangs indefinitely, close() must still return within CLOSE_AWAIT_TIMEOUT
     * by disposing the outstanding subscription. This verifies the timeout+dispose safety net
     * works without waiting the full 5-minute FLUSH_TIMEOUT.
     */
    @Test
    void close_disposesHungFlush_andReturnsPromptly(@TempDir Path tmp) throws Exception {
        WorkspaceManager wsm = new WorkspaceManager(tmp);

        Model hangingModel = mock(Model.class);
        when(hangingModel.stream(any(), any(), any()))
                .thenAnswer(inv -> Flux.<ChatResponse>never());

        MemoryFlushMiddleware mw =
                new MemoryFlushMiddleware(
                        wsm,
                        hangingModel,
                        MemoryFlushManager.DEFAULT_FLUSH_PROMPT,
                        MemoryConfig.FlushTrigger.always());

        RuntimeContext rc = RuntimeContext.builder().sessionId("s1").userId("u1").build();
        rc.setAgentState(stateWithMessages(userMsg("hello")));
        AgentInput input = new AgentInput(List.of(userMsg("hello")));

        // Trigger a flush that will hang (model never returns)
        mw.onAgent((Agent) null, rc, input, in -> Flux.<AgentEvent>empty())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertTrue(mw.hasPendingFlushes(), "flush should be pending (hanging)");

        // close() should dispose the hung flush and return within the await timeout (5s)
        long start = System.nanoTime();
        mw.close();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(
                elapsedMs < 6000,
                "close() should return within CLOSE_AWAIT_TIMEOUT even with hung flush, took "
                        + elapsedMs
                        + "ms");
        assertFalse(mw.hasPendingFlushes(), "no pending flushes should remain after close()");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Polls until the middleware has no pending background subscriptions. Throws
     * AssertionError if the timeout expires with pending work still in flight, so a
     * slow CI cannot silently mask a scheduling bug.
     */
    private static void awaitPendingSettled(MemoryFlushMiddleware mw, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!mw.hasPendingFlushes()) {
                return;
            }
            Thread.sleep(10);
        }
        assertFalse(mw.hasPendingFlushes(), "pending flushes should have settled");
    }

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
