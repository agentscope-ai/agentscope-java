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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Verifies that {@link MemoryFlushMiddleware#onAgent} completes the agent stream before the
 * memory flush finishes — the flush is fire-and-forget so a slow extraction must never delay
 * the caller — and that concurrent flushes for the same isolation key are serialised.
 */
class MemoryFlushMiddlewareAsyncFlushTest {

    @Test
    void onCompleteFiresBeforeAsyncFlushCompletes() throws Exception {
        // The flush stream is gated behind a latch so it stays in-flight while the agent stream
        // completes; the latch is released at the end of the test so the background task (and
        // its in-flight tracker) does not leak into other tests.
        CountDownLatch flushStarted = new CountDownLatch(1);
        CountDownLatch flushFinishGate = new CountDownLatch(1);
        AtomicReference<List<Msg>> flushedMessages = new AtomicReference<>();
        Model gatedModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        flushedMessages.set(messages);
                        flushStarted.countDown();
                        return Flux.defer(
                                () -> {
                                    try {
                                        flushFinishGate.await(5, TimeUnit.SECONDS);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return Flux.just(stubChunk());
                                });
                    }

                    @Override
                    public String getModelName() {
                        return "gated-model";
                    }
                };

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .textContent("remember: deploys happen on Fridays")
                        .build();
        AgentState state = AgentState.builder().addMessage(userMsg).build();
        RuntimeContext rc = RuntimeContext.builder().agentState(state).build();
        MemoryFlushMiddleware middleware = new MemoryFlushMiddleware(null, gatedModel);

        AgentEndEvent event = new AgentEndEvent("reply-1");
        try {
            // The agent stream must finish while the flush is still blocked on the gate.
            List<AgentEvent> emitted;
            try {
                emitted =
                        middleware
                                .onAgent(
                                        null,
                                        rc,
                                        new AgentInput(List.of(userMsg)),
                                        input -> Flux.just(event))
                                .collectList()
                                .block(Duration.ofSeconds(2));
            } catch (Exception e) {
                throw new AssertionError(
                        "agent stream must complete before the (gated) flush does", e);
            }

            assertEquals(List.of(event), emitted, "agent events must be passed through unchanged");
            assertTrue(
                    flushStarted.await(5, TimeUnit.SECONDS),
                    "async flush must still be triggered after the stream completes");
            assertTrue(
                    flushedMessages.get() != null && !flushedMessages.get().isEmpty(),
                    "flush must receive the conversation messages");
        } finally {
            // Release the gate so the background task (and its in-flight tracker) quiesces.
            flushFinishGate.countDown();
        }
        assertTrue(
                MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS),
                "released async flush must finish");
    }

    @Test
    void flushesForSameIsolationKeyRunSerially() throws Exception {
        // Two gated model invocations: each blocks until the test releases it. The second must
        // not start while the first is still in flight (same userId → same isolation key).
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch[] started = {new CountDownLatch(1), new CountDownLatch(1)};
        CountDownLatch[] release = {new CountDownLatch(1), new CountDownLatch(1)};
        Model gatedModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        int i = calls.getAndIncrement();
                        return Flux.defer(
                                () -> {
                                    int now = concurrent.incrementAndGet();
                                    maxConcurrent.accumulateAndGet(now, Math::max);
                                    started[i].countDown();
                                    try {
                                        release[i].await(10, TimeUnit.SECONDS);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    concurrent.decrementAndGet();
                                    return Flux.just(stubChunk());
                                });
                    }

                    @Override
                    public String getModelName() {
                        return "gated-model";
                    }
                };

        Msg userMsg =
                Msg.builder().role(MsgRole.USER).textContent("remember: deploy Fridays").build();
        AgentState state = AgentState.builder().addMessage(userMsg).build();
        RuntimeContext rc = RuntimeContext.builder().userId("alice").agentState(state).build();
        MemoryFlushMiddleware middleware = new MemoryFlushMiddleware(null, gatedModel);
        AgentInput input = new AgentInput(List.of(userMsg));
        AgentEndEvent event = new AgentEndEvent("reply-1");

        try {
            // Turn 1: flush starts and blocks on its gate.
            middleware
                    .onAgent(null, rc, input, in -> Flux.just(event))
                    .collectList()
                    .block(Duration.ofSeconds(2));
            assertTrue(started[0].await(5, TimeUnit.SECONDS), "first flush must run");

            // Turn 2 (same key): queued behind the still-running first flush.
            middleware
                    .onAgent(null, rc, input, in -> Flux.just(event))
                    .collectList()
                    .block(Duration.ofSeconds(2));
            assertFalse(
                    started[1].await(250, TimeUnit.MILLISECONDS),
                    "second flush must not start while the first is in flight");

            // Release the first → the queued second runs afterwards.
            release[0].countDown();
            assertTrue(
                    started[1].await(5, TimeUnit.SECONDS),
                    "queued second flush must run once the first finishes");
            release[1].countDown();
            assertTrue(
                    MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS),
                    "all flushes must quiesce after release");
        } finally {
            release[0].countDown();
            release[1].countDown();
            MemoryBackgroundTasks.awaitQuiescence(10, TimeUnit.SECONDS);
        }
        assertEquals(
                1, maxConcurrent.get(), "at most one flush per isolation key may run at a time");
    }

    @Test
    void backToBackSameSessionFlushesCoalesceIntoOnePendingTask() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch[] started = {
            new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)
        };
        CountDownLatch[] release = {
            new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)
        };
        Model gatedModel = gatedModel(calls, started, release);

        Msg userMsg =
                Msg.builder().role(MsgRole.USER).textContent("remember: deploy Fridays").build();
        AgentState state = AgentState.builder().addMessage(userMsg).build();
        RuntimeContext rc =
                RuntimeContext.builder()
                        .userId("alice")
                        .sessionId("session1")
                        .agentState(state)
                        .build();
        MemoryFlushMiddleware middleware = new MemoryFlushMiddleware(null, gatedModel);
        AgentInput input = new AgentInput(List.of(userMsg));
        AgentEndEvent event = new AgentEndEvent("reply-1");

        try {
            // Turn 1: flush starts and blocks on its gate.
            middleware
                    .onAgent(null, rc, input, in -> Flux.just(event))
                    .collectList()
                    .block(Duration.ofSeconds(2));
            assertTrue(started[0].await(5, TimeUnit.SECONDS), "first flush must run");

            // Turns 2 and 3 (same session) queue behind the running flush and must coalesce to a
            // single pending task: each queued flush snapshots the conversation at execution
            // time, so the newest one covers everything its predecessors would have extracted.
            middleware
                    .onAgent(null, rc, input, in -> Flux.just(event))
                    .collectList()
                    .block(Duration.ofSeconds(2));
            middleware
                    .onAgent(null, rc, input, in -> Flux.just(event))
                    .collectList()
                    .block(Duration.ofSeconds(2));
            assertFalse(
                    started[1].await(250, TimeUnit.MILLISECONDS),
                    "queued flushes must not start while the first is in flight");

            // Release the first → exactly one coalesced flush runs afterwards.
            release[0].countDown();
            assertTrue(
                    started[1].await(5, TimeUnit.SECONDS),
                    "coalesced flush must run once the first finishes");
            release[1].countDown();
            assertTrue(
                    MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS),
                    "flushes must quiesce after release");
            assertEquals(
                    2,
                    calls.get(),
                    "same-session back-to-back flushes must coalesce into one pending task");
        } finally {
            for (CountDownLatch latch : release) {
                latch.countDown();
            }
            MemoryBackgroundTasks.awaitQuiescence(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void sameUserDifferentSessionsEachKeepTheirPendingFlush() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch[] started = {
            new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)
        };
        CountDownLatch[] release = {
            new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)
        };
        Model gatedModel = gatedModel(calls, started, release);

        Msg userMsg =
                Msg.builder().role(MsgRole.USER).textContent("remember: deploy Fridays").build();
        RuntimeContext rcA =
                RuntimeContext.builder()
                        .userId("alice")
                        .sessionId("sessionA")
                        .agentState(AgentState.builder().addMessage(userMsg).build())
                        .build();
        RuntimeContext rcB =
                RuntimeContext.builder()
                        .userId("alice")
                        .sessionId("sessionB")
                        .agentState(AgentState.builder().addMessage(userMsg).build())
                        .build();
        MemoryFlushMiddleware middleware = new MemoryFlushMiddleware(null, gatedModel);
        AgentInput input = new AgentInput(List.of(userMsg));
        AgentEndEvent event = new AgentEndEvent("reply-1");

        try {
            // Session A: flush starts and blocks on its gate.
            middleware
                    .onAgent(null, rcA, input, in -> Flux.just(event))
                    .collectList()
                    .block(Duration.ofSeconds(2));
            assertTrue(started[0].await(5, TimeUnit.SECONDS), "session A's first flush must run");

            // Sessions B and A queue behind it, and B's second turn coalesces onto B's pending
            // task — but session A's pending task must survive: a different conversation.
            middleware
                    .onAgent(null, rcB, input, in -> Flux.just(event))
                    .collectList()
                    .block(Duration.ofSeconds(2));
            middleware
                    .onAgent(null, rcA, input, in -> Flux.just(event))
                    .collectList()
                    .block(Duration.ofSeconds(2));
            middleware
                    .onAgent(null, rcB, input, in -> Flux.just(event))
                    .collectList()
                    .block(Duration.ofSeconds(2));
            assertFalse(
                    started[1].await(250, TimeUnit.MILLISECONDS),
                    "queued flushes must not start while the first is in flight");

            release[0].countDown();
            assertTrue(
                    started[1].await(5, TimeUnit.SECONDS),
                    "first queued flush must run once the running one finishes");
            release[1].countDown();
            assertTrue(
                    started[2].await(5, TimeUnit.SECONDS),
                    "second queued flush must run afterwards");
            release[2].countDown();
            assertTrue(
                    MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS),
                    "flushes must quiesce after release");
            assertEquals(
                    3,
                    calls.get(),
                    "one flush per distinct session must still run — coalescing is per session,"
                            + " not per isolation key");
        } finally {
            for (CountDownLatch latch : release) {
                latch.countDown();
            }
            MemoryBackgroundTasks.awaitQuiescence(10, TimeUnit.SECONDS);
        }
    }

    /** The single streamed chunk every stub model in this class emits once unblocked. */
    private static ChatResponse stubChunk() {
        return new ChatResponse(
                "stub-id",
                List.of(TextBlock.builder().text("extracted memory").build()),
                null,
                Map.of(),
                "stop");
    }

    /**
     * A model whose {@code i}-th invocation signals {@code started[i]} and blocks on {@code
     * release[i]}.
     */
    private static Model gatedModel(
            AtomicInteger calls, CountDownLatch[] started, CountDownLatch[] release) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                int i = calls.getAndIncrement();
                return Flux.defer(
                        () -> {
                            started[i].countDown();
                            try {
                                release[i].await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return Flux.just(stubChunk());
                        });
            }

            @Override
            public String getModelName() {
                return "gated-model";
            }
        };
    }
}
