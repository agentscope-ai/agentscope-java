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
package io.agentscope.core.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.shutdown.GracefulShutdownMiddleware;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Tests for the {@link MiddlewareBase#activePoints()} participation-switch contract: default
 * full-set compatibility, four-state declaration semantics, per-point filtering, order
 * preservation, empty-chain short-circuit, null handling, and construction-time freezing.
 */
class MiddlewareActivePointsTest {

    /** Records entry at every onion hook and appends a marker to the system prompt. */
    private static class RecordingMiddleware implements MiddlewareBase {
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
            trace.add(tag + ":onAgent");
            return next.apply(input);
        }

        @Override
        public Flux<AgentEvent> onReasoning(
                Agent agent,
                RuntimeContext ctx,
                ReasoningInput input,
                Function<ReasoningInput, Flux<AgentEvent>> next) {
            trace.add(tag + ":onReasoning");
            return next.apply(input);
        }

        @Override
        public Flux<AgentEvent> onActing(
                Agent agent,
                RuntimeContext ctx,
                ActingInput input,
                Function<ActingInput, Flux<AgentEvent>> next) {
            trace.add(tag + ":onActing");
            return next.apply(input);
        }

        @Override
        public Flux<AgentEvent> onModelCall(
                Agent agent,
                RuntimeContext ctx,
                ModelCallInput input,
                Function<ModelCallInput, Flux<AgentEvent>> next) {
            trace.add(tag + ":onModelCall");
            return next.apply(input);
        }

        @Override
        public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
            trace.add(tag + ":onSystemPrompt");
            return Mono.just(currentPrompt + "|" + tag);
        }
    }

    /** Fixed-text model that captures the model input for system-prompt assertions. */
    private static final class CapturingModel extends ChatModelBase {
        private final List<List<Msg>> inputs = new ArrayList<>();

        @Override
        public String getModelName() {
            return "capturing";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(messages));
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text("ok").build()))
                            .build());
        }

        String firstInputText() {
            return inputs.get(0).get(0).getTextContent();
        }
    }

    private static ReActAgent buildAgent(Model model, List<MiddlewareBase> middlewares) {
        return ReActAgent.builder()
                .name("asst")
                .sysPrompt("base-system")
                .model(model)
                .toolkit(new Toolkit())
                .middlewares(middlewares)
                .build();
    }

    @Test
    void defaultAndNullDeclarationsBothActivateAllPoints() {
        List<String> trace = new ArrayList<>();
        CapturingModel model = new CapturingModel();
        MiddlewareBase nullDeclaring =
                new RecordingMiddleware("B", trace) {
                    @Override
                    public Set<ExtensionPoint> activePoints() {
                        return null;
                    }
                };
        ReActAgent agent =
                buildAgent(model, List.of(new RecordingMiddleware("A", trace), nullDeclaring));

        agent.streamEvents(List.of()).collectList().block();

        // The default and a null declaration are equivalent: both activate every point.
        assertTrue(
                trace.containsAll(
                        List.of(
                                "A:onAgent",
                                "B:onAgent",
                                "A:onReasoning",
                                "B:onReasoning",
                                "A:onModelCall",
                                "B:onModelCall")),
                trace.toString());
        assertEquals("base-system|A|B", model.firstInputText());
    }

    @Test
    void overriddenButUndeclaredPointsAreSkipped() {
        List<String> trace = new ArrayList<>();
        CapturingModel model = new CapturingModel();
        MiddlewareBase selective =
                new RecordingMiddleware("A", trace) {
                    @Override
                    public Set<ExtensionPoint> activePoints() {
                        return EnumSet.of(ExtensionPoint.ON_REASONING);
                    }
                };
        ReActAgent agent = buildAgent(model, List.of(selective));

        agent.streamEvents(List.of()).collectList().block();

        assertEquals(List.of("A:onReasoning"), trace);
        // The system-prompt point has no participants, so the original prompt passes through.
        assertEquals("base-system", model.firstInputText());
    }

    @Test
    void declaredButNotOverriddenRunsDefaultPassThrough() {
        MiddlewareBase redundant =
                new MiddlewareBase() {
                    @Override
                    public Set<ExtensionPoint> activePoints() {
                        return EnumSet.of(ExtensionPoint.ON_MODEL_CALL);
                    }
                };
        ReActAgent agent = buildAgent(new CapturingModel(), List.of(redundant));

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();

        assertNotNull(events);
        assertTrue(
                events.stream()
                        .anyMatch(
                                e ->
                                        e instanceof TextBlockDeltaEvent
                                                && "ok"
                                                        .equals(
                                                                ((TextBlockDeltaEvent) e)
                                                                        .getDelta())),
                "default pass-through must keep model events flowing");
    }

    @Test
    void emptySetDeactivatesEveryPointButKeepsRegistration() {
        List<String> trace = new ArrayList<>();
        MiddlewareBase off =
                new RecordingMiddleware("A", trace) {
                    @Override
                    public Set<ExtensionPoint> activePoints() {
                        return EnumSet.noneOf(ExtensionPoint.class);
                    }
                };
        ReActAgent agent = buildAgent(new CapturingModel(), List.of(off));

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();

        assertTrue(trace.isEmpty(), "no hook may fire: " + trace);
        assertTrue(agent.getMiddlewares().contains(off), "registration is retained");
        assertNotNull(events);
        assertFalse(events.isEmpty());
    }

    @Test
    void perPointFilteringPreservesOrder() {
        List<String> trace = new ArrayList<>();
        MiddlewareBase outer =
                new RecordingMiddleware("outer", trace) {
                    @Override
                    public Set<ExtensionPoint> activePoints() {
                        return EnumSet.of(ExtensionPoint.ON_AGENT);
                    }

                    @Override
                    public int order() {
                        return 2;
                    }
                };
        MiddlewareBase inner =
                new RecordingMiddleware("inner", trace) {
                    @Override
                    public Set<ExtensionPoint> activePoints() {
                        return EnumSet.of(ExtensionPoint.ON_AGENT);
                    }
                };
        ReActAgent agent = buildAgent(new CapturingModel(), List.of(inner, outer));

        agent.streamEvents(List.of()).collectList().block();

        // Higher order is outermost; only the two declared participants appear at the point.
        assertEquals(
                List.of("outer:onAgent", "inner:onAgent"),
                trace.stream().filter(s -> s.endsWith(":onAgent")).toList());
    }

    @Test
    void onionPointWithNoParticipantsBehavesLikeNoMiddleware() {
        List<String> inertTrace = new ArrayList<>();
        MiddlewareBase systemPromptOnly =
                new RecordingMiddleware("A", inertTrace) {
                    @Override
                    public Set<ExtensionPoint> activePoints() {
                        return EnumSet.of(ExtensionPoint.ON_SYSTEM_PROMPT);
                    }
                };
        CapturingModel bareModel = new CapturingModel();
        CapturingModel filteredModel = new CapturingModel();

        List<AgentEvent> bare =
                buildAgent(bareModel, List.of()).streamEvents(List.of()).collectList().block();
        List<AgentEvent> filtered =
                buildAgent(filteredModel, List.of(systemPromptOnly))
                        .streamEvents(List.of())
                        .collectList()
                        .block();

        assertEquals(List.of("A:onSystemPrompt"), inertTrace);
        List<String> bareTypes = bare.stream().map(e -> e.getClass().getSimpleName()).toList();
        List<String> filteredTypes =
                filtered.stream().map(e -> e.getClass().getSimpleName()).toList();
        assertEquals(bareTypes, filteredTypes, "empty onion chain must pass the core through");
    }

    @Test
    void declarationIsFrozenAtAgentConstruction() {
        List<String> trace = new ArrayList<>();
        EnumSet<MiddlewareBase.ExtensionPoint> mutable =
                EnumSet.of(MiddlewareBase.ExtensionPoint.ON_AGENT);
        MiddlewareBase declared =
                new RecordingMiddleware("A", trace) {
                    @Override
                    public Set<ExtensionPoint> activePoints() {
                        return mutable;
                    }
                };
        ReActAgent agent = buildAgent(new CapturingModel(), List.of(declared));
        mutable.clear();

        agent.streamEvents(List.of()).collectList().block();

        assertTrue(trace.contains("A:onAgent"), "post-build mutation must not affect the agent");
        assertFalse(trace.contains("A:onSystemPrompt"), trace.toString());
    }

    @Test
    void actingParticipantsRunAndUndeclaredOnesDoNot() {
        List<String> trace = new ArrayList<>();
        MiddlewareBase actingOnly =
                new RecordingMiddleware("A", trace) {
                    @Override
                    public Set<ExtensionPoint> activePoints() {
                        return EnumSet.of(ExtensionPoint.ON_ACTING);
                    }
                };
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("base-system")
                        .model(new ToolThenTextModel())
                        .toolkit(toolkitWithEchoTool())
                        .middlewares(List.of(actingOnly))
                        .build();

        agent.streamEvents(List.of()).collectList().block();

        assertEquals(List.of("A:onActing"), trace);
    }

    @Test
    void stateReadyParticipantsRunAndUndeclaredOnesDoNot() {
        List<String> trace = new ArrayList<>();
        AtomicReference<AgentState> stateParam = new AtomicReference<>();
        AtomicReference<AgentState> boundAtNotify = new AtomicReference<>();
        MiddlewareBase participant =
                new MiddlewareBase() {
                    @Override
                    public Set<ExtensionPoint> activePoints() {
                        return EnumSet.of(ExtensionPoint.ON_AGENT_STATE_READY);
                    }

                    @Override
                    public void onAgentStateReady(
                            Agent agent,
                            RuntimeContext ctx,
                            AgentState state,
                            List<Msg> inputMessages) {
                        trace.add("participant:onAgentStateReady");
                        stateParam.set(state);
                        boundAtNotify.set(ctx.getAgentState());
                    }
                };
        MiddlewareBase overriddenButUndeclared =
                new MiddlewareBase() {
                    @Override
                    public Set<ExtensionPoint> activePoints() {
                        return EnumSet.of(ExtensionPoint.ON_REASONING);
                    }

                    @Override
                    public void onAgentStateReady(
                            Agent agent,
                            RuntimeContext ctx,
                            AgentState state,
                            List<Msg> inputMessages) {
                        trace.add("undeclared:onAgentStateReady");
                    }
                };
        ReActAgent agent =
                buildAgent(new CapturingModel(), List.of(participant, overriddenButUndeclared));

        agent.streamEvents(List.of()).collectList().block();

        assertEquals(List.of("participant:onAgentStateReady"), trace);
        assertNotNull(boundAtNotify.get(), "state must already be bound to the RuntimeContext");
        assertSame(stateParam.get(), boundAtNotify.get(), "param state is ctx.getAgentState()");
    }

    @Test
    void middlewaresAtExposesPerPointImmutableSnapshot() {
        List<String> trace = new ArrayList<>();
        MiddlewareBase reasoningOnly =
                new RecordingMiddleware("A", trace) {
                    @Override
                    public Set<ExtensionPoint> activePoints() {
                        return EnumSet.of(ExtensionPoint.ON_REASONING);
                    }
                };
        ReActAgent agent = buildAgent(new CapturingModel(), List.of(reasoningOnly));

        List<MiddlewareBase> reasoning =
                agent.middlewaresAt(MiddlewareBase.ExtensionPoint.ON_REASONING);
        List<MiddlewareBase> prompt =
                agent.middlewaresAt(MiddlewareBase.ExtensionPoint.ON_SYSTEM_PROMPT);

        // GracefulShutdownMiddleware is auto-registered first and also declares ON_REASONING.
        assertEquals(2, reasoning.size());
        assertTrue(reasoning.get(0) instanceof GracefulShutdownMiddleware);
        assertSame(reasoningOnly, reasoning.get(1));
        assertTrue(prompt.isEmpty(), "points without participants return an empty list");
        assertThrows(
                UnsupportedOperationException.class,
                () -> reasoning.add(reasoningOnly),
                "snapshot must be immutable");
    }

    private static Toolkit toolkitWithEchoTool() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(
                new ToolBase(
                        "echo",
                        "Echoes the text back",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("text", Map.of("type", "string"))),
                        true,
                        true,
                        false,
                        null,
                        false,
                        false) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> input, PermissionContextState ctx) {
                        return Mono.just(PermissionDecision.allow("allowed"));
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.just(ToolResultBlock.text("echoed"));
                    }
                });
        return toolkit;
    }

    /** Emits a tool call first, then a final text answer. */
    private static final class ToolThenTextModel extends ChatModelBase {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String getModelName() {
            return "tool-then-text";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (calls.getAndIncrement() == 0) {
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("tc-1")
                                                        .name("echo")
                                                        .input(Map.of("text", "hi"))
                                                        .build()))
                                .build());
            }
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(TextBlock.builder().text("done").build()))
                            .build());
        }
    }
}
