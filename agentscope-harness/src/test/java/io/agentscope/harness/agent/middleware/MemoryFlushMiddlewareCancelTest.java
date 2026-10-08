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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * End-to-end verification of the memory flush connection-leak fix. Reproduces the bug scenario:
 * the agent stream completes but the fire-and-forget flush model call hangs (never returns
 * {@code [DONE]}), so its HTTP connection would previously leak until the JVM exits.
 *
 * <p>The test asserts that (1) the hung flush keeps the in-flight task alive before cancellation,
 * and (2) {@link MemoryBackgroundTasks#cancelAll()} releases it — proving cancel propagation now
 * reaches the underlying model subscription. This mirrors the {@code streamEvents onComplete ->
 * background model call at 06.510 -> connection alive until 13.558} symptom reported in production.
 */
class MemoryFlushMiddlewareCancelTest {

    @Test
    void hungFlushIsReleasedByCancelAll() throws Exception {
        CountDownLatch flushStarted = new CountDownLatch(1);
        Model hangingModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        flushStarted.countDown();
                        // Never emits [DONE]: simulates a stuck SSE/HTTP connection.
                        return Flux.never();
                    }

                    @Override
                    public String getModelName() {
                        return "hanging-model";
                    }
                };

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .textContent("remember: deploys happen on Fridays")
                        .build();
        AgentState state = AgentState.builder().addMessage(userMsg).build();
        RuntimeContext rc = RuntimeContext.builder().agentState(state).build();
        MemoryFlushMiddleware middleware = new MemoryFlushMiddleware(null, hangingModel);
        AgentEndEvent event = new AgentEndEvent("reply-1");

        try {
            middleware
                    .onAgent(null, rc, new AgentInput(List.of(userMsg)), in -> Flux.just(event))
                    .collectList()
                    .block(Duration.ofSeconds(2));

            assertTrue(
                    flushStarted.await(5, TimeUnit.SECONDS),
                    "flush model.stream must be invoked (the real leaked path)");
            assertFalse(
                    MemoryBackgroundTasks.awaitQuiescence(1, TimeUnit.SECONDS),
                    "without cancel, the hung flush must keep in-flight > 0 (proves the leak"
                            + " existed)");

            MemoryBackgroundTasks.cancelAll();

            assertTrue(
                    MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS),
                    "after cancelAll the hung flush must quiesce (no leaked connection)");
        } finally {
            MemoryBackgroundTasks.cancelAll();
            MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS);
        }
    }
}
