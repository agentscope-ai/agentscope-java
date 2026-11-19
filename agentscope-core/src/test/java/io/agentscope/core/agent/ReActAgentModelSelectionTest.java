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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Flux;

/**
 * Model selection: a per-call pin ({@code RuntimeContext.put(Model.class, ...)}) outranks the
 * agent's two build-time forms — a lazy identity string resolved on the run's first model
 * boundary, or a direct instance.
 */
@DisplayName("ReActAgent model selection")
class ReActAgentModelSelectionTest {

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

    private static RuntimeContext ctx(String sessionId) {
        return RuntimeContext.builder().userId("u1").sessionId(sessionId).build();
    }

    @Test
    @DisplayName("a context pin outranks the agent's identity string and instance")
    void contextPinOutranksBothAgentForms() {
        CountingModel identityModel = new CountingModel("identity");
        CountingModel instanceModel = new CountingModel("instance");
        CountingModel pinned = new CountingModel("pinned");
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model(instanceModel)
                        .stateStore(new InMemoryAgentStateStore())
                        .build();
        ModelRegistry.register("sel:identity", identityModel);
        RuntimeContext context = ctx("sessA");
        context.put(Model.class, pinned);
        try {
            agent.call(List.of(userMsg("hello")), context).block(Duration.ofSeconds(10));
            assertTrue(pinned.streamCount() > 0);
            assertEquals(0, identityModel.streamCount());
            assertEquals(0, instanceModel.streamCount());
        } finally {
            ModelRegistry.reset();
        }
    }

    @Test
    @DisplayName("an identity string resolves lazily and is memoized once per context")
    void identityResolvesLazilyAndIsMemoizedPerContext() {
        CountingModel first = new CountingModel("first");
        CountingModel second = new CountingModel("second");
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model("sel:rotate")
                        .stateStore(new InMemoryAgentStateStore())
                        .build();
        assertNull(agent.getModel());
        assertEquals("sel:rotate", agent.getModelIdentity());

        ModelRegistry.register("sel:rotate", first);
        RuntimeContext context = ctx("sessA");
        try {
            // Direct resolution memoizes into the context; later lookups on the same context
            // reuse it, so a registry rotation between reads cannot switch the model.
            assertSame(first, agent.getModel(context));
            assertSame(first, context.get(Model.class), "memoized into the typed bag");

            ModelRegistry.register("sel:rotate", second);
            assertSame(first, agent.getModel(context), "same context keeps its memoized model");
            assertSame(second, agent.getModel(ctx("sessB")), "a fresh context re-resolves");
        } finally {
            ModelRegistry.reset();
        }
    }

    @Test
    @DisplayName("a registry rotation during a run never switches the model mid-response")
    void midRunRotationNeverSwitchesModel() {
        CountingModel roundOne = new CountingModel("round-one");
        CountingModel roundTwo = new CountingModel("round-two");
        CountingModel instance = new CountingModel("instance");
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model(instance)
                        .stateStore(new InMemoryAgentStateStore())
                        .build();
        ModelRegistry.register("sel:rotate", roundOne);
        try {
            RuntimeContext context = ctx("sessA");
            context.put(Model.class, roundOne);

            // Simulate the run reading its model at two boundaries with a rotation in between:
            // the first read pins the instance for the whole run.
            Model firstRead = agent.getModel(context);
            ModelRegistry.register("sel:rotate", roundTwo);
            Model secondRead = agent.getModel(context);

            assertSame(firstRead, secondRead, "the run's model is decided once at its start");
        } finally {
            ModelRegistry.reset();
        }
    }

    @Test
    @DisplayName(
            "an unresolvable identity fails the run's first model call, never silently falls back")
    void unresolvableIdentityFailsLoudly() {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model("sel:never-registered")
                        .stateStore(new InMemoryAgentStateStore())
                        .build();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        agent.call(List.of(userMsg("hello")), ctx("sessA"))
                                .block(Duration.ofSeconds(10)));
    }

    @Test
    @DisplayName("the two model(...) overloads are one slot: last setter wins")
    void modelOverloadsAreLastSetterWins() {
        CountingModel instance = new CountingModel("instance");
        ReActAgent.Builder builder = ReActAgent.builder().name("asst");

        try (ReActAgent identityLast = builder.model(instance).model("sel:x").build();
                ReActAgent instanceLast = builder.model("sel:y").model(instance).build()) {
            assertEquals("sel:x", identityLast.getModelIdentity());
            assertNull(identityLast.getModel());
            assertSame(instance, instanceLast.getModel());
            assertNull(instanceLast.getModelIdentity());
        }
    }

    @Test
    @DisplayName("without a pin the build-time instance serves the call")
    void instanceServesWithoutPin() {
        CountingModel defaultModel = new CountingModel("default");
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model(defaultModel)
                        .stateStore(new InMemoryAgentStateStore())
                        .build();
        agent.call(List.of(userMsg("hello")), ctx("sessA")).block(Duration.ofSeconds(10));
        assertTrue(defaultModel.streamCount() > 0);
    }

    @Test
    @DisplayName("a runtime failure of the effective model is still covered by the fallback model")
    void fallbackCoversEffectiveModelFailure() {
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
        RuntimeContext context = ctx("sessA");
        context.put(Model.class, failing);

        agent.call(List.of(userMsg("hello")), context).block(Duration.ofSeconds(10));

        assertTrue(failing.streamCount() > 0, "the effective model must be tried first");
        assertTrue(fallback.streamCount() > 0, "fallback must take over on provider failure");
    }

    @Test
    @Timeout(10)
    @DisplayName("a slow identity resolution runs off the subscriber's thread")
    void slowIdentityResolutionRunsOffSubscriberThread() throws Exception {
        CountDownLatch inFactory = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> factoryThread = new AtomicReference<>();
        ModelRegistry.registerFactory(
                "slow:(.+)",
                id -> {
                    factoryThread.set(Thread.currentThread().getName());
                    inFactory.countDown();
                    try {
                        releaseFactory.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new CountingModel("slow");
                });
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model("slow:alpha")
                        .stateStore(new InMemoryAgentStateStore())
                        .build();
        String subscriberThread = Thread.currentThread().getName();
        try {
            // subscribe() must return while the factory is still blocked: entry resolution
            // happens on boundedElastic, never on the subscribing (possibly event-loop) thread.
            agent.call(List.of(userMsg("hello")), ctx("sessA"))
                    .subscribe(m -> done.countDown(), e -> done.countDown());
            assertTrue(inFactory.await(5, TimeUnit.SECONDS), "factory must have started");
            assertNotEquals(subscriberThread, factoryThread.get());
            assertTrue(
                    factoryThread.get().contains("agentscope-model-request"),
                    "resolution ran on: " + factoryThread.get());
        } finally {
            releaseFactory.countDown();
            done.await(5, TimeUnit.SECONDS);
            ModelRegistry.reset();
        }
    }
}
