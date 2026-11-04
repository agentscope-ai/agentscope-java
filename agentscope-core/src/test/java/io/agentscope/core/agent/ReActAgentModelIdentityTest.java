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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Identity-mode model selection: {@code Builder.model(String)} stores a lazy model identity
 * ({@code contextId@modelId} composite or plain registry string) resolved through {@link
 * ModelRegistry} on every call.
 */
@DisplayName("ReActAgent model identity")
class ReActAgentModelIdentityTest {

    /** Model that records how many times it served a call. */
    private static final class CountingModel extends ChatModelBase {
        private final String name;
        private final AtomicInteger streamCount = new AtomicInteger();

        private CountingModel(String name) {
            this.name = name;
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
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text("ok").build()))
                            .build());
        }
    }

    private static Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static ReActAgent identityAgent(String modelIdentity) {
        return ReActAgent.builder()
                .name("asst")
                .sysPrompt("hi")
                .model(modelIdentity)
                .stateStore(new InMemoryAgentStateStore())
                .build();
    }

    @Test
    @DisplayName("an unregistered identity builds fine and resolves once registered")
    void identityResolvesLazilyAfterRegistration() {
        CountingModel identityModel = new CountingModel("identity-model");
        ReActAgent agent = identityAgent("test:lazy-identity");

        assertNull(agent.getModel());
        assertEquals("test:lazy-identity", agent.getModelIdentity());

        ModelRegistry.register("test:lazy-identity", identityModel);
        try {
            agent.call(List.of(userMsg("hello")), RuntimeContext.empty())
                    .block(Duration.ofSeconds(10));
            assertTrue(identityModel.streamCount() > 0, "the call must resolve the identity");
        } finally {
            ModelRegistry.reset();
        }
    }

    @Test
    @DisplayName("re-registering the referenced name takes effect for calls started after it")
    void rotationTakesEffectForSubsequentCalls() {
        CountingModel first = new CountingModel("first");
        CountingModel second = new CountingModel("second");
        ReActAgent agent = identityAgent("test:rotate");
        ModelRegistry.register("test:rotate", first);
        try {
            agent.call(List.of(userMsg("hello")), RuntimeContext.empty())
                    .block(Duration.ofSeconds(10));

            ModelRegistry.register("test:rotate", second);
            agent.call(List.of(userMsg("hello")), RuntimeContext.empty())
                    .block(Duration.ofSeconds(10));

            assertTrue(first.streamCount() > 0);
            assertTrue(second.streamCount() > 0, "rotation must apply to the later call");
        } finally {
            ModelRegistry.reset();
        }
    }

    @Test
    @DisplayName("a context modelIdentity takes precedence over the agent's identity string")
    void contextModelIdentityWinsOverAgentIdentity() {
        CountingModel agentModel = new CountingModel("agent-side");
        CountingModel ctxModel = new CountingModel("ctx");
        ReActAgent agent = identityAgent("test:agent-side");
        ModelRegistry.register("test:agent-side", agentModel);
        ModelRegistry.register("test:ctx", ctxModel);
        try {
            RuntimeContext ctx =
                    RuntimeContext.builder()
                            .userId("u1")
                            .sessionId("sessA")
                            .modelIdentity("test:ctx")
                            .build();
            agent.call(List.of(userMsg("hello")), ctx).block(Duration.ofSeconds(10));

            assertTrue(ctxModel.streamCount() > 0);
            assertEquals(0, agentModel.streamCount());
        } finally {
            ModelRegistry.reset();
        }
    }

    @Test
    @DisplayName("an unresolvable identity fails the call instead of falling back")
    void unresolvableIdentityFailsCall() {
        ReActAgent agent = identityAgent("test:never-registered");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        agent.call(List.of(userMsg("hello")), RuntimeContext.empty())
                                .block(Duration.ofSeconds(10)));
    }

    @Test
    @DisplayName("getModel(RuntimeContext) resolves the identity without a full call")
    void getModelResolvesIdentity() {
        CountingModel identityModel = new CountingModel("m");
        ReActAgent agent = identityAgent("test:direct");
        ModelRegistry.register("test:direct", identityModel);
        try {
            assertSame(identityModel, agent.getModel(RuntimeContext.empty()));
            assertSame(identityModel, agent.getModel(null));
        } finally {
            ModelRegistry.reset();
        }
    }

    @Test
    @DisplayName("a composite contextId@modelId resolves with the registered creation context")
    void compositeIdentity_resolvesWithCreationContext() {
        AtomicReference<String> seenApiKey = new AtomicReference<>();
        ModelRegistry.registerFactory(
                "dashscope:(.+)",
                (id, ctx) -> {
                    seenApiKey.set(ctx.getApiKey());
                    return new CountingModel(id);
                });
        ModelRegistry.registerCreationContext(
                "mytokenplan01", ModelCreationContext.builder().apiKey("plan-key").build());
        try {
            ReActAgent agent = identityAgent("mytokenplan01@dashscope:qwen-plus");

            Model resolved = agent.getModel(RuntimeContext.empty());

            assertEquals(
                    "plan-key",
                    seenApiKey.get(),
                    "the context pairing must travel with the identity");
            assertEquals("dashscope:qwen-plus", resolved.getModelName());
            assertSame(
                    resolved,
                    agent.getModel(null),
                    "same (contextId, modelId) reuses the cached instance");
        } finally {
            ModelRegistry.reset();
        }
    }

    @Test
    @DisplayName("a composite per-call identity overrides the agent's default model instance")
    void compositeIdentity_perCallContext() {
        CountingModel defaultModel = new CountingModel("default");
        CountingModel compositeModel = new CountingModel("composite");
        ModelRegistry.registerFactory("dashscope:(.+)", (id, ctx) -> compositeModel);
        ModelRegistry.registerCreationContext(
                "mytokenplan01", ModelCreationContext.builder().apiKey("plan-key").build());
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model(defaultModel)
                        .stateStore(new InMemoryAgentStateStore())
                        .build();
        try {
            RuntimeContext ctx =
                    RuntimeContext.builder()
                            .userId("u1")
                            .sessionId("sessA")
                            .modelIdentity("mytokenplan01@dashscope:qwen-plus")
                            .build();
            agent.call(List.of(userMsg("hello")), ctx).block(Duration.ofSeconds(10));

            assertTrue(compositeModel.streamCount() > 0);
            assertEquals(0, defaultModel.streamCount());
        } finally {
            ModelRegistry.reset();
        }
    }

    @Test
    @DisplayName("a composite identity with an unregistered contextId fails fast")
    void compositeIdentity_unknownContextFailsFast() {
        ReActAgent agent = identityAgent("no-such-plan@dashscope:qwen-plus");
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> agent.getModel(RuntimeContext.empty()));
        assertTrue(ex.getMessage().contains("No ModelCreationContext registered"));
    }

    @Test
    @DisplayName("builder copy() and fromAgent() carry the model identity")
    void copyAndFromAgentCarryModelIdentity() {
        ReActAgent.Builder builder = ReActAgent.builder().name("asst").model("test:copy-identity");
        try (ReActAgent direct = builder.build();
                ReActAgent copied = builder.copy().build();
                ReActAgent derived = ReActAgent.Builder.fromAgent(direct).build()) {
            assertEquals("test:copy-identity", direct.getModelIdentity());
            assertEquals("test:copy-identity", copied.getModelIdentity());
            assertEquals("test:copy-identity", derived.getModelIdentity());
            assertNull(direct.getModel());
        }
    }

    @Test
    @DisplayName("the two model(...) overloads override each other (last setter wins)")
    void modelOverloads_areLastSetterWins() {
        CountingModel instance = new CountingModel("instance");
        ReActAgent.Builder builder = ReActAgent.builder().name("asst");

        try (ReActAgent identityAfterInstance =
                        builder.model(instance).model("test:identity").build();
                ReActAgent instanceAfterIdentity =
                        builder.model("test:other-identity").model(instance).build()) {
            assertEquals("test:identity", identityAfterInstance.getModelIdentity());
            assertNull(identityAfterInstance.getModel());
            assertSame(instance, instanceAfterIdentity.getModel());
            assertNull(instanceAfterIdentity.getModelIdentity());
        }
    }
}
