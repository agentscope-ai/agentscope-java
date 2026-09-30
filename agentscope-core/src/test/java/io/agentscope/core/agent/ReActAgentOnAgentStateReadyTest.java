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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Behavior tests for the {@link MiddlewareBase#onAgentStateReady} notification hook. */
@DisplayName("ReActAgent onAgentStateReady middleware notification")
class ReActAgentOnAgentStateReadyTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Model that records each reasoning round's message view. */
    private static final class CapturingModel extends ChatModelBase {
        private final List<List<Msg>> rounds = new CopyOnWriteArrayList<>();

        @Override
        public String getModelName() {
            return "capturing";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            rounds.add(List.copyOf(messages));
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text("ok").build()))
                            .build());
        }
    }

    /** Records every notification; optionally adjusts the state or input in place. */
    private static final class ReadyRecorder implements MiddlewareBase {
        private final String tag;
        private final int order;
        private final List<String> trace;
        private final BiConsumer<AgentState, List<Msg>> action;
        private final AtomicInteger invocations = new AtomicInteger();
        private volatile AgentState lastState;
        private volatile RuntimeContext lastCtx;

        /** Context size observed at notification time; the state's context is live and grows. */
        private volatile int contextSizeAtReady = -1;

        ReadyRecorder(String tag, int order, List<String> trace) {
            this(tag, order, trace, (state, input) -> {});
        }

        ReadyRecorder(
                String tag,
                int order,
                List<String> trace,
                BiConsumer<AgentState, List<Msg>> action) {
            this.tag = tag;
            this.order = order;
            this.trace = trace;
            this.action = action;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public void onAgentStateReady(
                Agent agent, RuntimeContext ctx, AgentState state, List<Msg> inputMessages) {
            trace.add(tag);
            lastState = state;
            lastCtx = ctx;
            contextSizeAtReady = state.getContext().size();
            invocations.incrementAndGet();
            action.accept(state, inputMessages);
        }
    }

    private static ReActAgent.Builder baseBuilder(CapturingModel model) {
        return ReActAgent.builder().name("asst").model(model).toolkit(new Toolkit());
    }

    private static RuntimeContext rc(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    private static List<String> userTexts(List<Msg> messages) {
        return messages.stream()
                .filter(m -> m.getRole() == MsgRole.USER)
                .map(Msg::getTextContent)
                .toList();
    }

    @Test
    @DisplayName("fires on cold and warm starts with this call's slot state bound to ctx")
    void firesOnColdAndWarmStartsWithCallScopedState() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        ReadyRecorder recorder = new ReadyRecorder("r", 1, new CopyOnWriteArrayList<>());
        ReActAgent agent =
                baseBuilder(new CapturingModel()).stateStore(store).middleware(recorder).build();

        agent.call(List.of(new UserMessage("hello")), rc("u1", "s1")).block(TIMEOUT);
        assertEquals(1, recorder.invocations.get());
        AgentState cold = recorder.lastState;
        assertNotNull(cold);
        assertSame(cold, recorder.lastCtx.getAgentState(), "bound to this call's RuntimeContext");
        assertSame(cold, agent.getAgentState("u1", "s1"), "this call's slot state");
        assertEquals("s1", cold.getSessionId());
        assertEquals("u1", cold.getUserId());
        assertEquals(0, recorder.contextSizeAtReady, "cold start sees an empty context");

        agent.call(List.of(new UserMessage("again")), rc("u1", "s1")).block(TIMEOUT);
        assertEquals(2, recorder.invocations.get());
        assertTrue(recorder.contextSizeAtReady > 0, "warm start sees restored history");
    }

    @Test
    @DisplayName("consumers run in order() sequence, higher first")
    void consumersRunInOrderSequence() {
        List<String> trace = new CopyOnWriteArrayList<>();
        ReActAgent agent =
                baseBuilder(new CapturingModel())
                        .middlewares(
                                List.of(
                                        new ReadyRecorder("low", 1, trace),
                                        new ReadyRecorder("high", 5, trace),
                                        new ReadyRecorder("mid", 3, trace)))
                        .build();

        agent.streamEvents(List.of(new UserMessage("hi"))).collectList().block(TIMEOUT);

        assertEquals(List.of("high", "mid", "low"), trace);
    }

    @Test
    @DisplayName("in-place input adjustment reaches the model and spares the caller's list")
    void inputAdjustmentReachesModelAndSparesCallerList() {
        CapturingModel model = new CapturingModel();
        ReActAgent agent =
                baseBuilder(model)
                        .middleware(
                                new ReadyRecorder(
                                        "r",
                                        1,
                                        new CopyOnWriteArrayList<>(),
                                        (state, input) -> input.add(0, new UserMessage("prefix"))))
                        .build();

        List<Msg> original = List.of(new UserMessage("hello"));
        agent.call(original, rc("u3", "s3")).block(TIMEOUT);

        assertEquals(
                List.of("prefix", "hello"),
                userTexts(model.rounds.get(0)),
                "model must see the adjusted input");
        assertEquals(List.of("hello"), userTexts(original), "caller's original list is untouched");
    }

    @Test
    @DisplayName("state mutation lands in the store via the save path")
    void stateMutationPersistedViaSavePath() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        ReActAgent agent =
                baseBuilder(new CapturingModel())
                        .stateStore(store)
                        .middleware(
                                new ReadyRecorder(
                                        "r",
                                        1,
                                        new CopyOnWriteArrayList<>(),
                                        (state, input) -> {
                                            if (state.getContext().isEmpty()) {
                                                state.contextMutable().add(new UserMessage("boot"));
                                            }
                                        }))
                        .build();

        agent.call(List.of(new UserMessage("hello")), rc("u4", "s4")).block(TIMEOUT);

        AgentState saved = store.get("u4", "s4", "agent_state", AgentState.class).orElseThrow();
        assertTrue(
                saved.getContext().stream().anyMatch(m -> "boot".equals(m.getTextContent())),
                "middleware-written state must be persisted");
        assertTrue(
                saved.getContext().stream().anyMatch(m -> "hello".equals(m.getTextContent())),
                "regular turn messages must also be persisted");
    }

    @Test
    @DisplayName("middleware without the override behaves exactly as before")
    void noOverrideKeepsExistingBehavior() {
        CapturingModel model = new CapturingModel();
        List<String> trace = new CopyOnWriteArrayList<>();
        MiddlewareBase plainOnAgent =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onAgent(
                            Agent agent,
                            RuntimeContext ctx,
                            AgentInput input,
                            Function<AgentInput, Flux<AgentEvent>> next) {
                        trace.add("onAgent");
                        return next.apply(input);
                    }
                };
        ReActAgent agent = baseBuilder(model).middleware(plainOnAgent).build();

        List<Msg> original = List.of(new UserMessage("hello"));
        agent.call(original, rc("u5", "s5")).block(TIMEOUT);

        assertEquals(List.of("onAgent"), trace, "declared hooks still run");
        assertEquals(
                List.of("hello"), userTexts(model.rounds.get(0)), "input must flow unmodified");
        assertEquals(List.of("hello"), userTexts(original));
    }

    /** Throws on its next notification after {@link #arm()}, then disarms itself. */
    private static final class ArmingFailingMiddleware implements MiddlewareBase {
        private final AtomicBoolean armed = new AtomicBoolean();

        void arm() {
            armed.set(true);
        }

        @Override
        public int order() {
            return 5;
        }

        @Override
        public void onAgentStateReady(
                Agent agent, RuntimeContext ctx, AgentState state, List<Msg> inputMessages) {
            if (armed.compareAndSet(true, false)) {
                throw new IllegalStateException("boom");
            }
        }
    }

    @Test
    @DisplayName("consumer exception propagates unchanged and stops the remaining consumers")
    void consumerExceptionPropagatesAndStopsRemaining() {
        CapturingModel model = new CapturingModel();
        ArmingFailingMiddleware failing = new ArmingFailingMiddleware();
        List<String> trace = new CopyOnWriteArrayList<>();
        ReadyRecorder after = new ReadyRecorder("after", 1, trace);
        ReActAgent agent = baseBuilder(model).middlewares(List.of(failing, after)).build();

        failing.arm();
        IllegalStateException callError =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                agent.call(List.of(new UserMessage("hi")), rc("u6", "s6"))
                                        .block(TIMEOUT));
        assertEquals("boom", callError.getMessage(), "exception must propagate unchanged");
        assertTrue(trace.isEmpty(), "consumers after the failing one must not run");
        assertTrue(model.rounds.isEmpty(), "model must not run after the failure");

        // The per-session gate must be released so the next same-session call proceeds.
        agent.call(List.of(new UserMessage("retry")), rc("u6", "s6")).block(TIMEOUT);
        assertEquals(List.of("after"), trace, "later consumers run once the failure is gone");
        assertEquals(1, model.rounds.size());

        failing.arm();
        IllegalStateException streamError =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                agent.streamEvents(
                                                List.of(new UserMessage("stream")), rc("u6", "s6"))
                                        .collectList()
                                        .block(TIMEOUT));
        assertEquals("boom", streamError.getMessage(), "exception must propagate unchanged");
        assertEquals(1, model.rounds.size(), "model must not run after the stream failure");
    }

    @Test
    @DisplayName("call() and streamEvents() trigger the notification alike")
    void callAndStreamEventsTriggerAlike() {
        AtomicInteger calls = new AtomicInteger();
        MiddlewareBase counter =
                new MiddlewareBase() {
                    @Override
                    public void onAgentStateReady(
                            Agent agent, RuntimeContext ctx, AgentState state, List<Msg> input) {
                        calls.incrementAndGet();
                    }
                };
        ReActAgent agent = baseBuilder(new CapturingModel()).middleware(counter).build();

        agent.call(List.of(new UserMessage("a")), rc("u7", "s7a")).block(TIMEOUT);
        assertEquals(1, calls.get());
        agent.streamEvents(List.of(new UserMessage("b")), rc("u7", "s7b"))
                .collectList()
                .block(TIMEOUT);
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("re-subscription re-fires with a fresh input copy")
    void resubscriptionRefiresWithFreshInputCopy() {
        List<List<String>> inputsAtReady = new CopyOnWriteArrayList<>();
        MiddlewareBase probing =
                new MiddlewareBase() {
                    @Override
                    public void onAgentStateReady(
                            Agent agent, RuntimeContext ctx, AgentState state, List<Msg> input) {
                        inputsAtReady.add(userTexts(input));
                    }
                };
        ReActAgent agent = baseBuilder(new CapturingModel()).middleware(probing).build();

        Mono<Msg> call = agent.call(List.of(new UserMessage("hello")), rc("u8", "s8"));
        call.block(TIMEOUT);
        call.block(TIMEOUT);

        assertEquals(2, inputsAtReady.size(), "each subscription re-fires the notification");
        assertEquals(
                List.of(List.of("hello"), List.of("hello")),
                inputsAtReady,
                "each subscription must start from a fresh copy of the caller's input");
    }

    @Test
    @DisplayName("each session's notification sees its own slot state")
    void distinctCallScopedStatePerSession() {
        Map<String, AgentState> stateBySession = new ConcurrentHashMap<>();
        MiddlewareBase recorder =
                new MiddlewareBase() {
                    @Override
                    public void onAgentStateReady(
                            Agent agent, RuntimeContext ctx, AgentState state, List<Msg> input) {
                        stateBySession.put(ctx.getSessionId(), state);
                    }
                };
        ReActAgent agent = baseBuilder(new CapturingModel()).middleware(recorder).build();

        agent.call(List.of(new UserMessage("a")), rc("uA", "sA")).block(TIMEOUT);
        agent.call(List.of(new UserMessage("b")), rc("uB", "sB")).block(TIMEOUT);

        assertEquals(Set.of("sA", "sB"), stateBySession.keySet());
        assertEquals("sA", stateBySession.get("sA").getSessionId());
        assertEquals("sB", stateBySession.get("sB").getSessionId());
        assertNotSame(stateBySession.get("sA"), stateBySession.get("sB"));
    }

    @Test
    @DisplayName("an interrupt issued during the notification window is honored, not dropped")
    void interruptDuringNotificationWindowIsHonored() throws Exception {
        CountDownLatch hookEntered = new CountDownLatch(1);
        CountDownLatch releaseHook = new CountDownLatch(1);
        MiddlewareBase blocker =
                new MiddlewareBase() {
                    @Override
                    public void onAgentStateReady(
                            Agent agent, RuntimeContext ctx, AgentState state, List<Msg> input) {
                        hookEntered.countDown();
                        try {
                            releaseHook.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                };
        ReActAgent agent = baseBuilder(new CapturingModel()).middleware(blocker).build();

        CompletableFuture<Msg> future =
                agent.call(List.of(new UserMessage("hello")), rc("u9", "s9"))
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();

        assertTrue(
                hookEntered.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                "notification should start");
        agent.interrupt("u9", "s9");
        releaseHook.countDown();

        Msg reply = future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(
                GenerateReason.INTERRUPTED,
                reply.getGenerateReason(),
                "interrupt during the notification window must be honored");
    }
}
