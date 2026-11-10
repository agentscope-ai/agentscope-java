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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Per-call model selection via {@link RuntimeContext#getModelId()}: routing, isolation, fail-fast
 * on unresolvable ids, and interaction with the configured fallback model.
 */
@DisplayName("ReActAgent per-call model selection")
class ReActAgentPerCallModelTest {

    /** Model that records how many times it served a call. */
    private static final class CountingModel extends ChatModelBase {
        private final String name;
        private final AtomicInteger streamCount = new AtomicInteger();
        private final boolean failFirstSignal;

        private CountingModel(String name) {
            this(name, false);
        }

        private CountingModel(String name, boolean failFirstSignal) {
            this.name = name;
            this.failFirstSignal = failFirstSignal;
        }

        @Override
        public String getModelName() {
            return name;
        }

        int streamCount() {
            return streamCount.get();
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            streamCount.incrementAndGet();
            if (failFirstSignal) {
                return Flux.error(new IllegalStateException("provider unavailable"));
            }
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text("ok").build()))
                            .build());
        }
    }

    private static Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private ReActAgent agent(CountingModel defaultModel) {
        return ReActAgent.builder()
                .name("asst")
                .sysPrompt("hi")
                .model(defaultModel)
                .stateStore(new InMemoryAgentStateStore())
                .build();
    }

    @Test
    @DisplayName("a context modelId routes the call through the registered model")
    void modelIdRoutesThroughRegistryModel() {
        CountingModel defaultModel = new CountingModel("default");
        CountingModel perCall = new CountingModel("per-call");
        ReActAgent agent = agent(defaultModel);
        ModelRegistry.register("test:per-call", perCall);
        try {
            RuntimeContext ctx =
                    RuntimeContext.builder()
                            .userId("u1")
                            .sessionId("sessA")
                            .modelId("test:per-call")
                            .build();
            agent.call(List.of(userMsg("hello")), ctx).block(Duration.ofSeconds(10));

            assertTrue(
                    perCall.streamCount() > 0, "reasoning must route through the per-call model");
            assertEquals(0, defaultModel.streamCount(), "default model must not serve the call");
        } finally {
            ModelRegistry.reset();
        }
    }

    @Test
    @DisplayName("without a modelId the agent's default model serves the call")
    void nullModelIdUsesDefaultModel() {
        CountingModel defaultModel = new CountingModel("default");
        ReActAgent agent = agent(defaultModel);
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("sessA").build();
        agent.call(List.of(userMsg("hello")), ctx).block(Duration.ofSeconds(10));
        assertTrue(defaultModel.streamCount() > 0);
    }

    @Test
    @DisplayName("model selection is scoped to the context that carries it")
    void contextsAreIsolated() {
        CountingModel defaultModel = new CountingModel("default");
        CountingModel modelA = new CountingModel("model-a");
        CountingModel modelB = new CountingModel("model-b");
        ReActAgent agent = agent(defaultModel);
        ModelRegistry.register("test:a", modelA);
        ModelRegistry.register("test:b", modelB);
        try {
            agent.call(
                            List.of(userMsg("hello")),
                            RuntimeContext.builder()
                                    .userId("u1")
                                    .sessionId("sessA")
                                    .modelId("test:a")
                                    .build())
                    .block(Duration.ofSeconds(10));
            agent.call(
                            List.of(userMsg("hello")),
                            RuntimeContext.builder()
                                    .userId("u1")
                                    .sessionId("sessB")
                                    .modelId("test:b")
                                    .build())
                    .block(Duration.ofSeconds(10));

            assertTrue(modelA.streamCount() > 0);
            assertTrue(modelB.streamCount() > 0);
            assertEquals(0, defaultModel.streamCount());
        } finally {
            ModelRegistry.reset();
        }
    }

    @Test
    @DisplayName("an unresolvable modelId fails the call instead of falling back silently")
    void unresolvableModelIdFailsCall() {
        CountingModel defaultModel = new CountingModel("default");
        ReActAgent agent = agent(defaultModel);
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .userId("u1")
                        .sessionId("sessA")
                        .modelId("test:does-not-exist")
                        .build();
        assertThrows(
                IllegalArgumentException.class,
                () -> agent.call(List.of(userMsg("hello")), ctx).block(Duration.ofSeconds(10)));
        assertEquals(0, defaultModel.streamCount(), "default model must not rescue a bad id");
    }

    @Test
    @DisplayName("a blank modelId fails the call (caller bug, not an unset choice)")
    void blankModelIdFailsCall() {
        CountingModel defaultModel = new CountingModel("default");
        ReActAgent agent = agent(defaultModel);
        RuntimeContext ctx =
                RuntimeContext.builder().userId("u1").sessionId("sessA").modelId(" ").build();
        assertThrows(
                IllegalArgumentException.class,
                () -> agent.call(List.of(userMsg("hello")), ctx).block(Duration.ofSeconds(10)));
        assertEquals(0, defaultModel.streamCount());
    }

    @Test
    @DisplayName("a runtime failure of the selected model is still covered by the fallback model")
    void fallbackModelServesWhenSelectedModelFails() {
        CountingModel defaultModel = new CountingModel("default");
        CountingModel failing = new CountingModel("failing", true);
        CountingModel fallback = new CountingModel("fallback");
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model(defaultModel)
                        .fallbackModel(fallback)
                        .stateStore(new InMemoryAgentStateStore())
                        .build();
        ModelRegistry.register("test:failing", failing);
        try {
            RuntimeContext ctx =
                    RuntimeContext.builder()
                            .userId("u1")
                            .sessionId("sessA")
                            .modelId("test:failing")
                            .build();
            agent.call(List.of(userMsg("hello")), ctx).block(Duration.ofSeconds(10));

            assertTrue(failing.streamCount() > 0, "the selected model must be tried first");
            assertTrue(fallback.streamCount() > 0, "fallback must take over on provider failure");
        } finally {
            ModelRegistry.reset();
        }
    }
}
