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
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.coordination.LocalPeriodicGate;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** Regression coverage for the response-completion delay reported in issue #2821. */
class MemoryFlushMiddlewareCompletionTest {

    @Test
    void asyncFlush_completesResponseWithoutWaitingForMemoryFlush() throws InterruptedException {
        Sinks.Many<ChatResponse> memoryResponse = Sinks.many().unicast().onBackpressureBuffer();
        CountDownLatch memoryModelStarted = new CountDownLatch(1);
        CountDownLatch memoryModelFinished = new CountDownLatch(1);
        Model slowMemoryModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        memoryModelStarted.countDown();
                        return memoryResponse
                                .asFlux()
                                .doFinally(ignored -> memoryModelFinished.countDown());
                    }

                    @Override
                    public String getModelName() {
                        return "controllable-memory-model";
                    }
                };

        MemoryFlushMiddleware middleware = asyncMiddleware(slowMemoryModel);

        AgentState state = stateWithUserMessage("Remember this");
        RuntimeContext context = RuntimeContext.builder().agentState(state).build();
        AgentEvent downstreamEvent = new CustomEvent("downstream-complete");

        StepVerifier.create(
                        middleware.onAgent(
                                mock(Agent.class),
                                context,
                                null,
                                ignored -> Flux.just(downstreamEvent)))
                .expectNext(downstreamEvent)
                .verifyComplete();

        assertTrue(
                memoryModelStarted.await(5, TimeUnit.SECONDS),
                "memory flush should start in the background");
        assertTrue(
                memoryResponse.tryEmitComplete().isSuccess(),
                "test should release the background memory flush");
        assertTrue(
                memoryModelFinished.await(5, TimeUnit.SECONDS),
                "background memory flush should terminate after release");
    }

    @Test
    void asyncFlush_failureIsIsolatedFromResponse() throws InterruptedException {
        CountDownLatch memoryModelStarted = new CountDownLatch(1);
        CountDownLatch memoryModelFinished = new CountDownLatch(1);
        Model failingMemoryModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        memoryModelStarted.countDown();
                        return Flux.<ChatResponse>error(new IllegalStateException("flush failed"))
                                .doFinally(ignored -> memoryModelFinished.countDown());
                    }

                    @Override
                    public String getModelName() {
                        return "failing-memory-model";
                    }
                };

        RuntimeContext context =
                RuntimeContext.builder().agentState(stateWithUserMessage("Remember this")).build();
        AgentEvent downstreamEvent = new CustomEvent("downstream-complete");

        StepVerifier.create(
                        asyncMiddleware(failingMemoryModel)
                                .onAgent(
                                        mock(Agent.class),
                                        context,
                                        null,
                                        ignored -> Flux.just(downstreamEvent)))
                .expectNext(downstreamEvent)
                .verifyComplete();

        assertTrue(
                memoryModelStarted.await(5, TimeUnit.SECONDS),
                "failing memory flush should still start in the background");
        assertTrue(
                memoryModelFinished.await(5, TimeUnit.SECONDS),
                "failing memory flush should be consumed and terminate");
    }

    @Test
    void asyncFlush_usesCompletionTimeMessageSnapshot() throws InterruptedException {
        Sinks.Many<ChatResponse> firstResponse = Sinks.many().unicast().onBackpressureBuffer();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        AtomicReference<List<Msg>> secondFlushInput = new AtomicReference<>();
        Model queuedMemoryModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        if (invocations.incrementAndGet() == 1) {
                            firstStarted.countDown();
                            return firstResponse.asFlux();
                        }
                        secondFlushInput.set(List.copyOf(messages));
                        secondStarted.countDown();
                        return Flux.<ChatResponse>empty()
                                .doFinally(ignored -> secondFinished.countDown());
                    }

                    @Override
                    public String getModelName() {
                        return "queued-memory-model";
                    }
                };
        MemoryFlushMiddleware middleware = asyncMiddleware(queuedMemoryModel);

        try {
            completeDownstream(middleware, stateWithUserMessage("first call"));
            assertTrue(
                    firstStarted.await(5, TimeUnit.SECONDS),
                    "first flush should occupy the serial scheduler");

            AgentState secondState = stateWithUserMessage("snapshot-before-mutation");
            completeDownstream(middleware, secondState);
            secondState.contextMutable().clear();
            secondState.contextMutable().add(userMessage("mutated-after-completion"));
        } finally {
            firstResponse.tryEmitComplete();
        }
        assertTrue(
                secondStarted.await(5, TimeUnit.SECONDS),
                "queued flush should start after the first flush finishes");
        assertTrue(
                secondFinished.await(5, TimeUnit.SECONDS),
                "queued flush should terminate before the test finishes");

        String flushPrompt =
                secondFlushInput.get().stream()
                        .map(Msg::getTextContent)
                        .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(flushPrompt.contains("snapshot-before-mutation"));
        assertFalse(flushPrompt.contains("mutated-after-completion"));
    }

    @Test
    void responseStream_waitsForMemoryFlushToComplete_currentBehavior() {
        Sinks.Many<ChatResponse> memoryResponse = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean memoryModelStarted = new AtomicBoolean();
        Model slowMemoryModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        memoryModelStarted.set(true);
                        return memoryResponse.asFlux();
                    }

                    @Override
                    public String getModelName() {
                        return "controllable-memory-model";
                    }
                };

        MemoryFlushMiddleware middleware =
                new MemoryFlushMiddleware(
                        mock(WorkspaceManager.class),
                        slowMemoryModel,
                        MemoryFlushManager.DEFAULT_FLUSH_PROMPT,
                        MemoryConfig.FlushTrigger.always(),
                        IsolationScope.USER);

        AgentState state = stateWithUserMessage("Remember this");
        RuntimeContext context = RuntimeContext.builder().agentState(state).build();
        AgentEvent downstreamEvent = new CustomEvent("downstream-complete");

        Flux<AgentEvent> result =
                middleware.onAgent(
                        mock(Agent.class), context, null, ignored -> Flux.just(downstreamEvent));

        StepVerifier.create(result)
                .expectNext(downstreamEvent)
                .expectNoEvent(Duration.ofMillis(200))
                .then(
                        () -> {
                            assertTrue(
                                    memoryModelStarted.get(),
                                    "memory flush should have started after the downstream event");
                            assertEquals(
                                    Sinks.EmitResult.OK,
                                    memoryResponse.tryEmitComplete(),
                                    "releasing the memory model should unblock response"
                                            + " completion");
                        })
                .verifyComplete();
    }

    private MemoryFlushMiddleware asyncMiddleware(Model model) {
        return new MemoryFlushMiddleware(
                mock(WorkspaceManager.class),
                model,
                MemoryFlushManager.DEFAULT_FLUSH_PROMPT,
                MemoryConfig.FlushTrigger.always(),
                IsolationScope.USER,
                new LocalPeriodicGate(),
                true);
    }

    private void completeDownstream(MemoryFlushMiddleware middleware, AgentState state) {
        RuntimeContext context = RuntimeContext.builder().agentState(state).build();
        middleware
                .onAgent(
                        mock(Agent.class),
                        context,
                        null,
                        ignored -> Flux.just(new CustomEvent("downstream-complete")))
                .then()
                .block(Duration.ofSeconds(5));
    }

    private AgentState stateWithUserMessage(String text) {
        return AgentState.builder().addMessage(userMessage(text)).build();
    }

    private Msg userMessage(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }
}
