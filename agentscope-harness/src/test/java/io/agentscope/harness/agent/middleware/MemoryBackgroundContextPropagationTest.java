/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Verifies that memory maintenance started on the agent stream's completion signal inherits the
 * subscriber's Reactor context. Context-scoped instrumentation (token metering, tracing) attached
 * to the model relies on it, and a fresh background subscription would otherwise start empty.
 */
class MemoryBackgroundContextPropagationTest {

    private static final String CONTEXT_KEY = "test-session";

    @TempDir Path workspace;

    @Test
    void flushModelSeesTheCompletingTurnContext() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        Model capturingModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.deferContextual(
                                context -> {
                                    seen.set(context.getOrDefault(CONTEXT_KEY, "missing"));
                                    return Flux.just(response("NO_REPLY"));
                                });
                    }

                    @Override
                    public String getModelName() {
                        return "capturing-model";
                    }
                };
        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent("remember this").build();
        RuntimeContext rc =
                RuntimeContext.builder()
                        .userId("alice")
                        .sessionId("session-1")
                        .agentState(AgentState.builder().addMessage(userMsg).build())
                        .build();
        MemoryFlushMiddleware middleware = new MemoryFlushMiddleware(null, capturingModel);

        middleware
                .onAgent(
                        null,
                        rc,
                        new AgentInput(List.of(userMsg)),
                        input -> Flux.just(new AgentEndEvent("reply-1")))
                .contextWrite(context -> context.put(CONTEXT_KEY, "session-1"))
                .blockLast(Duration.ofSeconds(2));

        assertTrue(
                MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS),
                "background flush must finish");
        assertEquals("session-1", seen.get(), "flush model must see the turn's Reactor context");
    }

    @Test
    void consolidationModelSeesTheCompletingTurnContext() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        WorkspaceManager workspaceManager = new WorkspaceManager(workspace);
        MemoryConsolidator consolidator =
                new MemoryConsolidator(workspaceManager, stubModel()) {
                    @Override
                    public Mono<Void> consolidate(RuntimeContext rc) {
                        return Mono.deferContextual(
                                context -> {
                                    seen.set(context.getOrDefault(CONTEXT_KEY, "missing"));
                                    return Mono.empty();
                                });
                    }
                };
        MemoryMaintenanceMiddleware middleware =
                new MemoryMaintenanceMiddleware(
                        workspaceManager,
                        consolidator,
                        90,
                        Duration.ofMinutes(30),
                        IsolationScope.USER,
                        (name, minGap) -> true);
        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        RuntimeContext rc =
                RuntimeContext.builder()
                        .userId("alice")
                        .sessionId("session-1")
                        .agentState(AgentState.builder().addMessage(userMsg).build())
                        .build();

        middleware
                .onAgent(
                        null,
                        rc,
                        new AgentInput(List.of(userMsg)),
                        input -> Flux.just(new AgentEndEvent("reply-1")))
                .contextWrite(context -> context.put(CONTEXT_KEY, "session-1"))
                .blockLast(Duration.ofSeconds(2));

        assertTrue(
                MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS),
                "background maintenance must finish");
        assertEquals(
                "session-1", seen.get(), "consolidation model must see the turn's Reactor context");
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(
                "stub-id", List.of(TextBlock.builder().text(text).build()), null, Map.of(), "stop");
    }

    private static Model stubModel() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(response("NO_REPLY"));
            }

            @Override
            public String getModelName() {
                return "stub-model";
            }
        };
    }
}
