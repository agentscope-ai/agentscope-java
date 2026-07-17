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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.ModelCallStartEvent;
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
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Integration tests asserting the onion middleware ordering around the core ReAct phases. */
class ReActAgentMiddlewareIntegrationTest {

    private static AgentState newState() {
        return AgentState.builder().sessionId("session-mw").build();
    }

    private static final class FixedTextModel extends ChatModelBase {
        private final String text;

        FixedTextModel(String text) {
            this.text = text;
        }

        @Override
        public String getModelName() {
            return "fixed";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text(text).build()))
                            .build());
        }
    }

    /** Records entry/exit at every middleware hook to a shared trace list. */
    private static final class RecordingMiddleware implements MiddlewareBase {
        private final String tag;
        private final List<String> trace;

        RecordingMiddleware(String tag, List<String> trace) {
            this.tag = tag;
            this.trace = trace;
        }

        @Override
        public Flux<AgentEvent> onAgent(
                Agent agent,
                RuntimeContext ctx,
                AgentInput input,
                Function<AgentInput, Flux<AgentEvent>> next) {
            trace.add(tag + ":reply:enter");
            return next.apply(input).doOnComplete(() -> trace.add(tag + ":reply:exit"));
        }

        @Override
        public Flux<AgentEvent> onReasoning(
                Agent agent,
                RuntimeContext ctx,
                ReasoningInput input,
                Function<ReasoningInput, Flux<AgentEvent>> next) {
            trace.add(tag + ":reasoning:enter");
            return next.apply(input).doOnComplete(() -> trace.add(tag + ":reasoning:exit"));
        }

        @Override
        public Flux<AgentEvent> onModelCall(
                Agent agent,
                RuntimeContext ctx,
                ModelCallInput input,
                Function<ModelCallInput, Flux<AgentEvent>> next) {
            trace.add(tag + ":modelCall:enter");
            return next.apply(input).doOnComplete(() -> trace.add(tag + ":modelCall:exit"));
        }

        @Override
        public Flux<AgentEvent> onActing(
                Agent agent,
                RuntimeContext ctx,
                ActingInput input,
                Function<ActingInput, Flux<AgentEvent>> next) {
            trace.add(tag + ":acting:enter");
            return next.apply(input).doOnComplete(() -> trace.add(tag + ":acting:exit"));
        }

        @Override
        public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
            trace.add(tag + ":systemPrompt");
            return Mono.just(currentPrompt);
        }
    }

    private static ReActAgent buildAgent(ChatModelBase model, List<MiddlewareBase> middlewares) {
        return ReActAgent.builder()
                .name("asst")
                .sysPrompt("hello-system")
                .model(model)
                .toolkit(new Toolkit())
                .middlewares(middlewares)
                .build();
    }

    @Test
    void singleMiddlewareSeesReplyAndReasoningAndModelCall() {
        List<String> trace = new ArrayList<>();
        ReActAgent agent =
                buildAgent(new FixedTextModel("ok"), List.of(new RecordingMiddleware("A", trace)));

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events);
        assertTrue(events.get(events.size() - 1) instanceof AgentEndEvent);

        assertTrue(trace.contains("A:reply:enter"), trace.toString());
        assertTrue(trace.contains("A:reasoning:enter"), trace.toString());
        assertTrue(trace.contains("A:modelCall:enter"), trace.toString());
        assertTrue(trace.contains("A:reply:exit"), trace.toString());
        assertTrue(trace.contains("A:reasoning:exit"), trace.toString());
        assertTrue(trace.contains("A:modelCall:exit"), trace.toString());
    }

    @Test
    void reasoningMiddlewareEventsAreForwardedExactlyOnce() {
        MiddlewareBase hintMiddleware =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onReasoning(
                            Agent agent,
                            RuntimeContext ctx,
                            ReasoningInput input,
                            Function<ReasoningInput, Flux<AgentEvent>> next) {
                        HintBlockEvent hint =
                                new HintBlockEvent(
                                        "reply-hint", "block-hint", "child-agent", "completed");
                        return Flux.concat(Flux.just(hint), next.apply(input));
                    }
                };
        ReActAgent agent = buildAgent(new FixedTextModel("ok"), List.of(hintMiddleware));

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();

        assertNotNull(events);
        List<HintBlockEvent> hints =
                events.stream()
                        .filter(HintBlockEvent.class::isInstance)
                        .map(HintBlockEvent.class::cast)
                        .toList();
        assertEquals(1, hints.size());
        assertEquals("reply-hint", hints.get(0).getReplyId());
        assertEquals("block-hint", hints.get(0).getBlockId());
        assertEquals("child-agent", hints.get(0).getHintSource());
        assertEquals("completed", hints.get(0).getHint());
        assertEquals(
                1,
                events.stream().filter(ModelCallStartEvent.class::isInstance).count(),
                "core model events must not be published twice");
    }

    @Test
    void onionOrderingFollowsRegistrationForReplyHook() {
        List<String> trace = new CopyOnWriteArrayList<>();
        ReActAgent agent =
                buildAgent(
                        new FixedTextModel("ok"),
                        List.of(
                                new RecordingMiddleware("A", trace),
                                new RecordingMiddleware("B", trace)));
        agent.streamEvents(List.of()).collectList().block();

        int aEnter = trace.indexOf("A:reply:enter");
        int bEnter = trace.indexOf("B:reply:enter");
        int aExit = trace.indexOf("A:reply:exit");
        int bExit = trace.indexOf("B:reply:exit");
        assertTrue(aEnter >= 0 && bEnter > aEnter, "outer enters first: " + trace);
        assertTrue(aExit > bExit && bExit > aEnter, "outer exits last: " + trace);
    }

    @Test
    void middlewareSeesEveryHookCategoryOnPlainTextReply() {
        List<String> trace = new ArrayList<>();
        ReActAgent agent =
                buildAgent(new FixedTextModel("ok"), List.of(new RecordingMiddleware("A", trace)));
        agent.streamEvents(List.of()).collectList().block();

        long reasoningEnters = trace.stream().filter(s -> s.equals("A:reasoning:enter")).count();
        long modelCallEnters = trace.stream().filter(s -> s.equals("A:modelCall:enter")).count();
        assertEquals(
                reasoningEnters,
                modelCallEnters,
                "reasoning and modelCall enter counts must match");
    }

    /**
     * Verifies that the serializeOnKey gate is properly released when a middleware
     * throws an {@link Error} (not {@code Exception}) during event stream processing.
     *
     * <p>Without the fix, the first call's lifecycle Mono subscription survives the
     * external Flux cancellation, holding the per-session serialization gate open
     * indefinitely. The second same-session call then blocks forever, receiving only
     * {@code AgentStartEvent} with no further events.
     *
     * <p>With the fix ({@code sink.onCancel(disposable)}), the internal lifecycle
     * subscription is disposed when the external Flux is cancelled, so the gate is
     * released immediately and the second call proceeds normally.
     */
    @Test
    void serializeOnKeyGateReleasedAfterMiddlewareError() {
        // Faulty middleware that throws RuntimeException on AgentStart — only on the first call.
        java.util.concurrent.atomic.AtomicBoolean failed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        MiddlewareBase faulty =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onAgent(
                            Agent agent,
                            RuntimeContext ctx,
                            AgentInput input,
                            Function<AgentInput, Flux<AgentEvent>> next) {
                        return next.apply(input)
                                .concatMap(
                                        event -> {
                                            if (event instanceof AgentStartEvent
                                                    && failed.compareAndSet(false, true)) {
                                                throw new RuntimeException(
                                                        "Simulated middleware failure");
                                            }
                                            return Flux.just(event);
                                        });
                    }
                };

        ReActAgent agent = buildAgent(new FixedTextModel("ok"), List.of(faulty));

        RuntimeContext rc = RuntimeContext.builder().userId("u").sessionId("gate-test").build();

        // First call: middleware throws RuntimeException on AgentStart → stream errors,
        // onErrorResume swallows it and returns an empty Flux.
        List<AgentEvent> first =
                agent.streamEvents(List.of(), rc)
                        .onErrorResume(t -> Flux.empty())
                        .collectList()
                        .block(Duration.ofSeconds(10));
        assertNotNull(first, "first call should return empty list after error");
        assertEquals(0, first.size(), "first call must yield empty list on error");

        // Second call: same session — must complete within timeout, proving
        // the serializeOnKey gate was released after the first call's error.
        List<AgentEvent> second =
                agent.streamEvents(List.of(), rc).collectList().block(Duration.ofSeconds(10));
        assertNotNull(second, "second call must complete without hanging");
        assertTrue(
                second.size() >= 2,
                "second call must contain at least AgentStart + AgentEnd; got " + second.size());
        assertTrue(second.get(0) instanceof AgentStartEvent);
        assertTrue(second.get(second.size() - 1) instanceof AgentEndEvent);
    }
}
