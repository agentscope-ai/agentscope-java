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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Resolution chain of {@link MemoryFlushMiddleware#effectiveFlushModel}. */
@DisplayName("MemoryFlushMiddleware effective model")
class MemoryFlushMiddlewareEffectiveModelTest {

    private static final Model DEDICATED = model("dedicated");
    private static final Model PER_CALL = model("per-call");

    private static Model model(String name) {
        return new ChatModelBase() {
            @Override
            public String getModelName() {
                return name;
            }

            @Override
            protected Flux<ChatResponse> doStream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(
                        ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text("ok").build()))
                                .build());
            }
        };
    }

    @Test
    @DisplayName("a dedicated flush model wins over the agent's per-call model")
    void dedicatedModelWins() {
        MemoryFlushMiddleware middleware = new MemoryFlushMiddleware(null, DEDICATED);
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getModel(any(RuntimeContext.class))).thenReturn(PER_CALL);
        assertSame(DEDICATED, middleware.effectiveFlushModel(agent, RuntimeContext.empty()));
    }

    @Test
    @DisplayName("without a dedicated model the agent's per-call model is used")
    void perCallModelWhenNoDedicated() {
        MemoryFlushMiddleware middleware = new MemoryFlushMiddleware(null, null);
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getModel(any(RuntimeContext.class))).thenReturn(PER_CALL);
        RuntimeContext ctx = RuntimeContext.builder().userId("u").sessionId("s").build();
        assertSame(PER_CALL, middleware.effectiveFlushModel(agent, ctx));
    }

    @Test
    @DisplayName("non-ReActAgent agents without a dedicated model yield no flush model")
    void nonReActAgentWithoutDedicatedYieldsNull() {
        MemoryFlushMiddleware middleware = new MemoryFlushMiddleware(null, null);
        Agent agent = mock(Agent.class);
        assertNull(middleware.effectiveFlushModel(agent, RuntimeContext.empty()));
    }
}
