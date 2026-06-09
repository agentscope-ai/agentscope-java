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
package io.agentscope.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ReActAgentAgentImplRuntimeContextTest {

    private static final class FixedTextModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "fixed";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text("ok").build()))
                            .build());
        }
    }

    private static final class CapturingMiddleware implements MiddlewareBase {
        private final boolean shortCircuit;
        private final AtomicReference<RuntimeContext> seen = new AtomicReference<>();

        private CapturingMiddleware(boolean shortCircuit) {
            this.shortCircuit = shortCircuit;
        }

        @Override
        public Flux<AgentEvent> onAgent(
                Agent agent,
                RuntimeContext ctx,
                AgentInput input,
                Function<AgentInput, Flux<AgentEvent>> next) {
            seen.set(ctx);
            return shortCircuit ? Flux.empty() : next.apply(input);
        }

        @Override
        public Flux<AgentEvent> onReasoning(
                Agent agent,
                RuntimeContext ctx,
                ReasoningInput input,
                Function<ReasoningInput, Flux<AgentEvent>> next) {
            return next.apply(input);
        }

        @Override
        public Flux<AgentEvent> onModelCall(
                Agent agent,
                RuntimeContext ctx,
                ModelCallInput input,
                Function<ModelCallInput, Flux<AgentEvent>> next) {
            return next.apply(input);
        }

        @Override
        public Flux<AgentEvent> onActing(
                Agent agent,
                RuntimeContext ctx,
                ActingInput input,
                Function<ActingInput, Flux<AgentEvent>> next) {
            return next.apply(input);
        }

        @Override
        public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
            return Mono.just(currentPrompt);
        }
    }

    private static ReActAgent buildAgent(MiddlewareBase middleware) {
        return ReActAgent.builder()
                .name("asst")
                .sysPrompt("hello-system")
                .model(new FixedTextModel())
                .toolkit(new Toolkit())
                .middlewares(List.of(middleware))
                .build();
    }

    @Test
    void streamEventsRunsCoreLifecycleWithRuntimeContext() {
        CapturingMiddleware middleware = new CapturingMiddleware(false);
        ReActAgent agent = buildAgent(middleware);
        RuntimeContext runtimeContext =
                RuntimeContext.builder().sessionId("runtime-context-session").build();

        List<AgentEvent> events =
                agent.streamEvents(List.of(), runtimeContext).collectList().block();

        assertNotNull(events);
        assertTrue(events.get(events.size() - 1) instanceof AgentEndEvent);
        assertSame(runtimeContext, middleware.seen.get());
        assertNull(agent.getRuntimeContext());
    }

    @Test
    void streamEventsClearsRuntimeContextWhenShortCircuited() {
        CapturingMiddleware middleware = new CapturingMiddleware(true);
        ReActAgent agent = buildAgent(middleware);
        RuntimeContext runtimeContext =
                RuntimeContext.builder().sessionId("runtime-context-session").build();

        List<AgentEvent> events =
                agent.streamEvents(List.of(), runtimeContext).collectList().block();

        assertNotNull(events);
        assertTrue(events.isEmpty(), events.toString());
        assertSame(runtimeContext, middleware.seen.get());
        assertNull(agent.getRuntimeContext());
    }
}
