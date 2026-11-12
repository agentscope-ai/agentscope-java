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
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.GracefulShutdownMiddleware;
import io.agentscope.core.skill.DynamicSkillMiddleware;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.search.ToolSearchMiddleware;
import io.agentscope.core.tracing.OtelTracingMiddleware;
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
 * Tests for the {@link MiddlewareBase#activePoints()} participation-switch contract: explicit
 * declarations are authoritative, {@code null} (the default) is auto-detected from implemented
 * hooks (own and inherited), plus per-point filtering, order preservation, empty-chain
 * short-circuit, construction-time freezing, and shipped middlewares declaring every point
 * whose hook they implement.
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
        public Set<ExtensionPoint> activePoints() {
            return EnumSet.of(
                    ExtensionPoint.ON_AGENT,
                    ExtensionPoint.ON_REASONING,
                    ExtensionPoint.ON_ACTING,
                    ExtensionPoint.ON_MODEL_CALL,
                    ExtensionPoint.ON_SYSTEM_PROMPT);
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

    /** Implements the system-prompt hook; does not declare {@code activePoints()}. */
    private static class PromptHookBase implements MiddlewareBase {
        @Override
        public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
            return Mono.just(currentPrompt + "|base");
        }
    }

    /** Adds a reasoning hook on top of {@link PromptHookBase}; still undeclared. */
    private static final class InheritingChild extends PromptHookBase {
        private final List<String> trace;

        InheritingChild(List<String> trace) {
            this.trace = trace;
        }

        @Override
        public Flux<AgentEvent> onReasoning(
                Agent agent,
                RuntimeContext ctx,
                ReasoningInput input,
                Function<ReasoningInput, Flux<AgentEvent>> next) {
            trace.add("child:onReasoning");
            return next.apply(input);
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
    void nullAndDefaultDeclarationsInferPointsFromImplementedHooks() {
        List<String> trace = new ArrayList<>();
        // Explicit null declaration: RecordingMiddleware implements every hook except
        // onAgentStateReady.
        MiddlewareBase nullDeclaring =
                new RecordingMiddleware("B", trace) {
                    @Override
                    public Set<ExtensionPoint> activePoints() {
                        return null;
                    }
                };
        // Undeclared (the default null): implements only the system-prompt hook.
        MiddlewareBase undeclared =
                new MiddlewareBase() {
                    @Override
                    public Mono<String> onSystemPrompt(
                            Agent agent, RuntimeContext ctx, String currentPrompt) {
                        return Mono.just(currentPrompt + "|inferred");
                    }
                };
        CapturingModel model = new CapturingModel();
        ReActAgent agent = buildAgent(model, List.of(nullDeclaring, undeclared));

        agent.streamEvents(List.of()).collectList().block();

        assertTrue(
                trace.containsAll(
                        List.of("B:onAgent", "B:onReasoning", "B:onModelCall", "B:onSystemPrompt")),
                trace.toString());
        // Pipeline applies participants in registration order (equal order() values).
        assertEquals("base-system|B|inferred", model.firstInputText());
        assertTrue(
                agent.middlewaresAt(MiddlewareBase.ExtensionPoint.ON_SYSTEM_PROMPT)
                        .containsAll(List.of(nullDeclaring, undeclared)),
                "implemented hooks' points are inferred");
        assertFalse(
                agent.middlewaresAt(MiddlewareBase.ExtensionPoint.ON_AGENT_STATE_READY)
                        .contains(nullDeclaring),
                "a point whose hook is not implemented is not inferred");
        assertFalse(
                agent.middlewaresAt(MiddlewareBase.ExtensionPoint.ON_ACTING).contains(undeclared),
                "points without an implemented hook are not inferred");
    }

    @Test
    void nullInferenceCountsInheritedHookImplementations() {
        CapturingModel model = new CapturingModel();
        List<String> trace = new ArrayList<>();
        MiddlewareBase child = new InheritingChild(trace);
        ReActAgent agent = buildAgent(model, List.of(child));

        agent.streamEvents(List.of()).collectList().block();

        // onSystemPrompt is implemented by the superclass, onReasoning by the child; the
        // inferred set covers both.
        assertEquals("base-system|base", model.firstInputText());
        assertEquals(List.of("child:onReasoning"), trace);
        assertTrue(
                agent.middlewaresAt(MiddlewareBase.ExtensionPoint.ON_SYSTEM_PROMPT).contains(child),
                "inherited implementations count");
    }

    @Test
    void nullWithNoImplementedHooksParticipatesNowhere() {
        MiddlewareBase bare = new MiddlewareBase() {};
        ReActAgent agent = buildAgent(new CapturingModel(), List.of(bare));

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();

        assertNotNull(events);
        assertFalse(events.isEmpty(), "the agent still runs");
        assertTrue(agent.getMiddlewares().contains(bare), "registration is retained");
        assertTrue(
                agent.middlewaresAt(MiddlewareBase.ExtensionPoint.ON_SYSTEM_PROMPT).isEmpty(),
                "a middleware implementing no hooks is not swept into any point");
    }

    @Test
    void builtinMiddlewaresDeclareEveryImplementedHookPoint() {
        List<MiddlewareBase> builtins =
                List.of(
                        new TaskReminderMiddleware(),
                        new FinalAnswerFilterMiddleware(),
                        new DynamicSkillMiddleware(List.of()),
                        new ToolSearchMiddleware(new Toolkit()),
                        new OtelTracingMiddleware(),
                        new GracefulShutdownMiddleware(GracefulShutdownManager.getInstance()));

        for (MiddlewareBase mw : builtins) {
            Set<MiddlewareBase.ExtensionPoint> inferred = ActivePointsResolver.infer(mw.getClass());
            Set<MiddlewareBase.ExtensionPoint> declared = mw.activePoints();
            assertTrue(
                    declared != null && declared.containsAll(inferred),
                    mw.getClass().getSimpleName()
                            + " implements hooks at "
                            + inferred
                            + " but declares "
                            + declared
                            + " — an implemented hook whose point is not declared is never"
                            + " invoked");
        }
    }

    @Test
    void explicitlyDeclaredAllPointsActivateThemAll() {
        List<String> trace = new ArrayList<>();
        CapturingModel model = new CapturingModel();
        ReActAgent agent = buildAgent(model, List.of(new RecordingMiddleware("A", trace)));

        agent.streamEvents(List.of()).collectList().block();

        assertTrue(
                trace.containsAll(
                        List.of("A:onAgent", "A:onReasoning", "A:onModelCall", "A:onSystemPrompt")),
                trace.toString());
        assertEquals("base-system|A", model.firstInputText());
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
