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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Verifies that {@link MemoryFlushMiddleware#onAgent} completes the agent stream before the
 * memory flush finishes — the flush is fire-and-forget so a slow extraction must never delay
 * the caller.
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
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text("extracted memory").build()),
                        null,
                        Map.of(),
                        "stop");
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
                                    return Flux.just(chunk);
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
}
