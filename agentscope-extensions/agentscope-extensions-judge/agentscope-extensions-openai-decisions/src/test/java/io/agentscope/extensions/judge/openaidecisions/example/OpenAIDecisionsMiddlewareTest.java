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
package io.agentscope.extensions.judge.openaidecisions.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.extensions.judge.openaidecisions.DecisionAnswer;
import io.agentscope.extensions.judge.openaidecisions.DecisionRequest;
import io.agentscope.extensions.judge.openaidecisions.DecisionResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Local stub decisions only; downstream captures are not provider or permission-engine tests. */
class OpenAIDecisionsMiddlewareTest {
    @Test
    void guardPublishesDenialToForwardingEmitterWithoutDuplicatingRawParentEvents() {
        var forwarded = new ArrayList<AgentEvent>();
        var raw = new ArrayList<AgentEvent>();
        var guard =
                OpenAIDecisionsAutoModeMiddleware.builder(
                                r -> Mono.just(result(Map.of("tool_0", predicate(0.1)))))
                        .enabled(true)
                        .guardedTool("bash")
                        .build();
        var ctx = RuntimeContext.empty();
        ctx.setAgentState(AgentState.builder().replyId("reply").build());
        var events =
                guard.onActing(
                                null,
                                ctx,
                                new ActingInput(List.of(call("x", "bash"))),
                                i -> Flux.empty())
                        .contextWrite(
                                c ->
                                        c.put(
                                                        AgentEventEmitter.CONTEXT_KEY,
                                                        (AgentEventEmitter) raw::add)
                                                .put(
                                                        AgentEventEmitter.FORWARDING_CONTEXT_KEY,
                                                        (AgentEventEmitter) forwarded::add))
                        .collectList()
                        .block();
        assertEquals(3, events.size());
        assertEquals(events, forwarded);
        assertTrue(raw.isEmpty());
    }

    private static final List<Msg> MESSAGES = List.of(new UserMessage("Find my order"));

    @Test
    void routesOncePerInvocationAndPreservesModelInput() {
        Model fallback = mock(Model.class);
        Model selected = mock(Model.class);
        var calls = new AtomicInteger();
        var captured = new AtomicReference<DecisionRequest>();
        var builder =
                OpenAIDecisionsModelRouterMiddleware.builder(
                                request -> {
                                    calls.incrementAndGet();
                                    captured.set(request);
                                    return Mono.just(
                                            result(Map.of("models_0", choice("fast", 0.9))));
                                })
                        .enabled(true)
                        .choice("fast", selected, "Simple requests")
                        .choice("slow", fallback, "Complex requests");
        var router = builder.build();
        builder.enabled(false).choice("fast", fallback, "Changed builder");
        var ctx = RuntimeContext.empty();
        router.onAgent(null, ctx, new AgentInput(MESSAGES), input -> Flux.empty()).blockLast();
        var options = GenerateOptions.builder().maxTokens(100).build();
        var input = new ModelCallInput(MESSAGES, List.of(), options, fallback);
        for (int i = 0; i < 2; i++) {
            var actual = route(router, ctx, input);
            assertSame(selected, actual.model());
            assertSame(MESSAGES, actual.messages());
            assertSame(input.tools(), actual.tools());
            assertSame(options, actual.options());
        }
        assertEquals(1, calls.get());
        assertEquals("Find my order", captured.get().input().text());
        assertEquals(0.9, OpenAIDecisionsModelRouterMiddleware.decision(ctx).confidence());
        // Reusing a context for a no-text invocation must not keep a prior route.
        router.onAgent(null, ctx, new AgentInput(List.of()), ignored -> Flux.empty()).blockLast();
        assertSame(input, route(router, ctx, input));
    }

    @Test
    void routerFallsBackOnRefusalLowConfidenceUnknownChoiceAndFailures() {
        Model fallback = mock(Model.class);
        for (DecisionAnswer answer :
                List.of(
                        new DecisionAnswer.Refusal(),
                        choice("fast", 0.2),
                        choice("unknown", 0.9))) {
            var router =
                    OpenAIDecisionsModelRouterMiddleware.builder(
                                    r -> Mono.just(result(Map.of("models_0", answer))))
                            .enabled(true)
                            .choice("fast", mock(Model.class), "Simple")
                            .choice("slow", fallback, "Complex")
                            .build();
            var ctx = RuntimeContext.empty();
            router.onAgent(null, ctx, new AgentInput(MESSAGES), i -> Flux.empty()).blockLast();
            var input = new ModelCallInput(MESSAGES, List.of(), null, fallback);
            assertSame(input, route(router, ctx, input));
        }
        var router =
                OpenAIDecisionsModelRouterMiddleware.builder(
                                r -> {
                                    throw new IllegalStateException("unavailable");
                                })
                        .enabled(true)
                        .choice("fast", mock(Model.class), "Simple")
                        .choice("slow", fallback, "Complex")
                        .build();
        router.onAgent(null, RuntimeContext.empty(), new AgentInput(MESSAGES), i -> Flux.empty())
                .blockLast();
        var strict =
                OpenAIDecisionsModelRouterMiddleware.builder(r -> Mono.empty())
                        .enabled(true)
                        .failOpen(false)
                        .choice("fast", mock(Model.class), "Simple")
                        .choice("slow", fallback, "Complex")
                        .build();
        StepVerifier.create(
                        strict.onAgent(
                                null,
                                RuntimeContext.empty(),
                                new AgentInput(MESSAGES),
                                i -> Flux.empty()))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void routerRequiresExplicitCompatibilityForToolCalls() {
        Model selected = mock(Model.class);
        var ctx = RuntimeContext.empty();
        var router =
                OpenAIDecisionsModelRouterMiddleware.builder(
                                r -> Mono.just(result(Map.of("models_0", choice("fast", 0.9)))))
                        .enabled(true)
                        .choice("fast", selected, "Simple")
                        .choice("slow", mock(Model.class), "Complex")
                        .build();
        router.onAgent(null, ctx, new AgentInput(MESSAGES), i -> Flux.empty()).blockLast();
        var input = new ModelCallInput(MESSAGES, List.of(tool("lookup")), null, mock(Model.class));
        assertSame(input, route(router, ctx, input));
        var compatible =
                OpenAIDecisionsModelRouterMiddleware.builder(
                                r -> Mono.just(result(Map.of("models_0", choice("fast", 0.9)))))
                        .enabled(true)
                        .compatible((i, c) -> true)
                        .choice("fast", selected, "Simple")
                        .choice("slow", mock(Model.class), "Complex")
                        .build();
        compatible.onAgent(null, ctx, new AgentInput(MESSAGES), i -> Flux.empty()).blockLast();
        assertSame(selected, route(compatible, ctx, input).model());
    }

    @Test
    void routingIsScopedToRuntimeContext() {
        Model selected = mock(Model.class);
        var router =
                OpenAIDecisionsModelRouterMiddleware.builder(
                                r -> Mono.just(result(Map.of("models_0", choice("fast", 0.9)))))
                        .enabled(true)
                        .choice("fast", selected, "Simple")
                        .choice("slow", mock(Model.class), "Complex")
                        .build();
        var ctx = RuntimeContext.empty();
        router.onAgent(null, ctx, new AgentInput(MESSAGES), i -> Flux.empty()).blockLast();
        var input = new ModelCallInput(MESSAGES, List.of(), null, mock(Model.class));
        assertSame(input, route(router, RuntimeContext.empty(), input));
    }

    @Test
    void selectionKeepsEssentialToolsCapsRelevantToolsAndPreservesOrder() {
        var captured = new AtomicReference<DecisionRequest>();
        var selector =
                OpenAIDecisionsToolSelectionMiddleware.builder(
                                r -> {
                                    captured.set(r);
                                    return Mono.just(
                                            result(
                                                    Map.of(
                                                            "tool_0",
                                                            predicate(0.85),
                                                            "tool_1",
                                                            predicate(0.95),
                                                            "tool_2",
                                                            predicate(0.05))));
                                })
                        .enabled(true)
                        .maxTools(1)
                        .build();
        var input =
                new ReasoningInput(
                        MESSAGES,
                        List.of(
                                tool("lookup"),
                                tool("generate_response"),
                                tool("search"),
                                tool("delete")),
                        null);
        var selected = select(selector, input);
        assertEquals(
                List.of("generate_response", "search"),
                selected.tools().stream().map(ToolSchema::getName).toList());
        assertSame(input.messages(), selected.messages());
        assertEquals(3, captured.get().questions().size());
        assertTrue(captured.get().input().text().contains("Find my order"));
    }

    @Test
    void selectionCanRemoveAllOptionalToolsAndDelegatesExactlyOnce() {
        var selector =
                OpenAIDecisionsToolSelectionMiddleware.builder(
                                r -> Mono.just(result(Map.of("tool_0", predicate(0.01)))))
                        .enabled(true)
                        .build();
        assertTrue(
                select(selector, new ReasoningInput(MESSAGES, List.of(tool("delete")), null))
                        .tools()
                        .isEmpty());
    }

    @Test
    void selectionRetainsOriginalToolsOnAmbiguityRefusalOrEmptyResult() {
        var input = new ReasoningInput(MESSAGES, List.of(tool("lookup")), null);
        for (DecisionAnswer answer : List.of(predicate(0.5), new DecisionAnswer.Refusal())) {
            var selector =
                    OpenAIDecisionsToolSelectionMiddleware.builder(
                                    r -> Mono.just(result(Map.of("tool_0", answer))))
                            .enabled(true)
                            .build();
            assertSame(input, select(selector, input));
        }
        assertSame(
                input,
                select(
                        OpenAIDecisionsToolSelectionMiddleware.builder(r -> Mono.empty())
                                .enabled(true)
                                .build(),
                        input));
        var strict =
                OpenAIDecisionsToolSelectionMiddleware.builder(r -> Mono.empty())
                        .enabled(true)
                        .failOpen(false)
                        .build();
        StepVerifier.create(
                        strict.onReasoning(null, RuntimeContext.empty(), input, i -> Flux.empty()))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void selectionBatchesMoreThan400Tools() {
        var sizes = new ArrayList<Integer>();
        var selector =
                OpenAIDecisionsToolSelectionMiddleware.builder(
                                r -> {
                                    sizes.add(r.questions().size());
                                    return Mono.just(predicates(r, 0.9));
                                })
                        .enabled(true)
                        .maxTools(3)
                        .build();
        var tools = IntStream.range(0, 401).mapToObj(i -> tool("tool" + i)).toList();
        assertEquals(3, select(selector, new ReasoningInput(MESSAGES, tools, null)).tools().size());
        assertEquals(List.of(400, 1), sizes);
    }

    @Test
    void selectionPreservesForcedToolsOutsideOptionalLimit() {
        var selector =
                OpenAIDecisionsToolSelectionMiddleware.builder(
                                request -> {
                                    assertEquals(2, request.questions().size());
                                    assertTrue(
                                            request.questions().values().stream()
                                                    .noneMatch(
                                                            q -> q.toString().contains("weather")));
                                    return Mono.just(predicates(request, 0.9));
                                })
                        .enabled(true)
                        .alwaysIncludeTools(java.util.Set.of())
                        .maxTools(1)
                        .build();
        var options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Specific("weather")).build();
        var input =
                new ReasoningInput(
                        MESSAGES,
                        List.of(tool("weather"), tool("search"), tool("lookup")),
                        options);
        var selected = select(selector, input);
        assertEquals(
                List.of("weather", "search"),
                selected.tools().stream().map(ToolSchema::getName).toList());
        assertSame(options, selected.options());
    }

    @Test
    void selectionDoesNotJudgeOnlyForcedToolOrInventMissingSchema() {
        var calls = new AtomicInteger();
        var selector =
                OpenAIDecisionsToolSelectionMiddleware.builder(
                                request -> {
                                    calls.incrementAndGet();
                                    return Mono.just(predicates(request, 0.0));
                                })
                        .enabled(true)
                        .build();
        var options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Specific("weather")).build();
        var onlyForced = new ReasoningInput(MESSAGES, List.of(tool("weather")), options);
        assertSame(onlyForced, select(selector, onlyForced));
        assertEquals(0, calls.get());
        var absentForced =
                select(selector, new ReasoningInput(MESSAGES, List.of(tool("search")), options));
        assertTrue(absentForced.tools().isEmpty());
        assertSame(options, absentForced.options());
        assertEquals(1, calls.get());
    }

    @Test
    void selectionUsesToolResultEvidence() {
        var selector =
                OpenAIDecisionsToolSelectionMiddleware.builder(
                                request -> {
                                    assertTrue(request.input().text().contains("Order shipped"));
                                    return Mono.just(predicates(request, 0.0));
                                })
                        .enabled(true)
                        .build();
        var messages =
                List.<Msg>of(
                        new UserMessage("Refund only if not shipped"),
                        new ToolResultMessage("query-1", "query_order", "Order shipped"));
        assertTrue(
                select(selector, new ReasoningInput(messages, List.of(tool("refund")), null))
                        .tools()
                        .isEmpty());
    }

    @Test
    void guardUsesToolResultEvidence() {
        for (boolean shipped : List.of(true, false)) {
            String evidence = shipped ? "Order shipped" : "Order not shipped";
            var state = AgentState.builder().replyId("reply").build();
            state.contextMutable().add(new UserMessage("Refund only if not shipped"));
            state.contextMutable().add(new ToolResultMessage("query-1", "query_order", evidence));
            var ctx = RuntimeContext.empty();
            ctx.setAgentState(state);
            var guard =
                    OpenAIDecisionsAutoModeMiddleware.builder(
                                    request -> {
                                        assertTrue(request.input().text().contains(evidence));
                                        return Mono.just(
                                                result(
                                                        Map.of(
                                                                "tool_0",
                                                                predicate(shipped ? 0.1 : 0.9))));
                                    })
                            .enabled(true)
                            .guardedTool("refund")
                            .build();
            var dispatched = new AtomicBoolean();
            var events =
                    guard.onActing(
                                    null,
                                    ctx,
                                    new ActingInput(List.of(call("refund-1", "refund"))),
                                    input -> {
                                        dispatched.set(true);
                                        return Flux.empty();
                                    })
                            .collectList()
                            .block();
            assertEquals(!shipped, dispatched.get());
            assertEquals(shipped ? 3 : 0, events.size());
        }
    }

    @Test
    void toolResultProjectionRespectsBudget() {
        var result = new ToolResultMessage("query-1", "query_order", "abc😀def");
        assertEquals(
                List.of(Map.of("role", "tool", "text", "abc")),
                DecisionSupport.recentMessages(List.of(result), 1, 4));
        assertEquals(
                List.of(Map.of("role", "tool", "text", "abc😀def")),
                DecisionSupport.recentMessages(List.of(new UserMessage("old"), result), 1, 100));
    }

    @Test
    void guardBlocksRiskAndRefusalPreservingOtherCallsAndDenialEvents() {
        var state = AgentState.builder().replyId("reply").build();
        var ctx = RuntimeContext.empty();
        ctx.setAgentState(state);
        var request = new AtomicReference<DecisionRequest>();
        var guard =
                OpenAIDecisionsAutoModeMiddleware.builder(
                                r -> {
                                    request.set(r);
                                    return Mono.just(
                                            result(
                                                    Map.of(
                                                            "tool_0",
                                                            predicate(0.9),
                                                            "tool_1",
                                                            predicate(0.1),
                                                            "tool_2",
                                                            new DecisionAnswer.Refusal())));
                                })
                        .enabled(true)
                        .failOpen(true)
                        .guardedTool("bash")
                        .build();
        var safe = call("safe", "bash");
        var risk = call("risk", "bash");
        var refusal = call("refusal", "bash");
        var read = call("read", "read_file");
        var confirmed = call("confirmed", "bash").withState(ToolCallState.ALLOWED);
        var captured = new AtomicReference<ActingInput>();
        List<AgentEvent> events =
                guard.onActing(
                                null,
                                ctx,
                                new ActingInput(List.of(safe, risk, read, refusal, confirmed)),
                                i -> {
                                    captured.set(i);
                                    return Flux.empty();
                                })
                        .collectList()
                        .block();
        assertEquals(List.of(safe, read, confirmed), captured.get().toolCalls());
        assertEquals(6, events.size());
        assertInstanceOf(ToolResultStartEvent.class, events.get(0));
        assertInstanceOf(ToolResultTextDeltaEvent.class, events.get(1));
        assertInstanceOf(ToolResultEndEvent.class, events.get(2));
        assertEquals(2, state.contextMutable().size());
        assertTrue(
                state.getContext().stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .allMatch(b -> b.getState() == ToolResultState.DENIED));
        assertEquals(3, request.get().questions().size());
        assertTrue(request.get().input().text().contains("command"));
    }

    @Test
    void guardFailsClosedForErrorsEmptyResultsAndMissingContext() {
        for (Function<DecisionRequest, Mono<DecisionResult>> fn :
                List.<Function<DecisionRequest, Mono<DecisionResult>>>of(
                        r -> Mono.error(new IllegalStateException("unavailable")),
                        r -> Mono.empty())) {
            var guard =
                    OpenAIDecisionsAutoModeMiddleware.builder(fn)
                            .enabled(true)
                            .guardedTool("bash")
                            .build();
            var ctx = RuntimeContext.empty();
            ctx.setAgentState(AgentState.builder().replyId("reply").build());
            assertEquals(
                    3,
                    guard.onActing(
                                    null,
                                    ctx,
                                    new ActingInput(List.of(call("x", "bash"))),
                                    i -> {
                                        fail("guarded call must not be dispatched");
                                        return Flux.empty();
                                    })
                            .collectList()
                            .block()
                            .size());
        }
        var missingState =
                OpenAIDecisionsAutoModeMiddleware.builder(
                                r -> {
                                    throw new AssertionError("no call without state");
                                })
                        .enabled(true)
                        .guardedTool("bash")
                        .build();
        assertEquals(
                3,
                missingState
                        .onActing(
                                null,
                                RuntimeContext.empty(),
                                new ActingInput(List.of(call("x", "bash"))),
                                i -> {
                                    fail("missing context must not authorize execution");
                                    return Flux.empty();
                                })
                        .collectList()
                        .block()
                        .size());
    }

    @Test
    void guardExplicitFailOpenAllowsTransportFailures() {
        var guard =
                OpenAIDecisionsAutoModeMiddleware.builder(
                                r -> Mono.error(new IllegalStateException("unavailable")))
                        .enabled(true)
                        .failOpen(true)
                        .guardedTool("bash")
                        .build();
        var ctx = RuntimeContext.empty();
        ctx.setAgentState(AgentState.builder().build());
        var input = new ActingInput(List.of(call("x", "bash")));
        var delegated = new AtomicBoolean();
        guard.onActing(
                        null,
                        ctx,
                        input,
                        i -> {
                            delegated.set(true);
                            assertSame(input, i);
                            return Flux.empty();
                        })
                .blockLast();
        assertTrue(delegated.get());
    }

    @Test
    void guardBatchesAndStillChecksCallsAfterFirst400() {
        var sizes = new ArrayList<Integer>();
        var guard =
                OpenAIDecisionsAutoModeMiddleware.builder(
                                r -> {
                                    sizes.add(r.questions().size());
                                    return Mono.just(predicates(r, 0.9));
                                })
                        .enabled(true)
                        .guardedTool("bash")
                        .build();
        var ctx = RuntimeContext.empty();
        ctx.setAgentState(AgentState.builder().build());
        var tools = IntStream.range(0, 401).mapToObj(i -> call("call" + i, "bash")).toList();
        var delegated = new AtomicInteger();
        guard.onActing(
                        null,
                        ctx,
                        new ActingInput(tools),
                        i -> {
                            delegated.set(i.toolCalls().size());
                            return Flux.empty();
                        })
                .blockLast();
        assertEquals(List.of(400, 1), sizes);
        assertEquals(401, delegated.get());
    }

    @Test
    void cancellationDoesNotDispatchToolsOrWriteDeniedResults() {
        var cancelled = new AtomicBoolean();
        var guard =
                OpenAIDecisionsAutoModeMiddleware.builder(
                                r ->
                                        Mono.<DecisionResult>never()
                                                .doOnCancel(() -> cancelled.set(true)))
                        .enabled(true)
                        .guardedTool("bash")
                        .build();
        var state = AgentState.builder().build();
        var ctx = RuntimeContext.empty();
        ctx.setAgentState(state);
        StepVerifier.create(
                        guard.onActing(
                                null,
                                ctx,
                                new ActingInput(List.of(call("x", "bash"))),
                                i -> {
                                    fail("cancelled assessment must not dispatch tools");
                                    return Flux.empty();
                                }))
                .thenCancel()
                .verify();
        assertTrue(cancelled.get());
        assertTrue(state.getContext().isEmpty());
    }

    @Test
    void middlewareIsDisabledByDefault() {
        Function<DecisionRequest, Mono<DecisionResult>> noCall =
                r -> {
                    throw new AssertionError("disabled");
                };
        var router =
                OpenAIDecisionsModelRouterMiddleware.builder(noCall)
                        .choice("fast", mock(Model.class), "Simple")
                        .choice("slow", mock(Model.class), "Complex")
                        .build();
        router.onAgent(null, RuntimeContext.empty(), new AgentInput(MESSAGES), i -> Flux.empty())
                .blockLast();
        var input = new ReasoningInput(MESSAGES, List.of(tool("lookup")), null);
        assertSame(
                input,
                select(OpenAIDecisionsToolSelectionMiddleware.builder(noCall).build(), input));
        OpenAIDecisionsAutoModeMiddleware.builder(noCall)
                .guardedTool("bash")
                .build()
                .onActing(
                        null,
                        RuntimeContext.empty(),
                        new ActingInput(List.of(call("x", "bash"))),
                        i -> Flux.empty())
                .blockLast();
    }

    @Test
    void validatesConfigurationAndBoundsConversationWithoutSplittingSurrogates() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        OpenAIDecisionsToolSelectionMiddleware.builder(r -> Mono.empty())
                                .confidenceThreshold(Double.NaN)
                                .build());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        OpenAIDecisionsToolSelectionMiddleware.builder(r -> Mono.empty())
                                .maxContextChars(0)
                                .build());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        OpenAIDecisionsAutoModeMiddleware.builder(r -> Mono.empty())
                                .guardedTool("bash")
                                .safetyThreshold(Double.NaN)
                                .build());
        assertThrows(
                IllegalArgumentException.class,
                () -> OpenAIDecisionsModelRouterMiddleware.builder(r -> Mono.empty()).build());
        assertEquals(
                List.of(Map.of("role", "user", "text", "a")),
                DecisionSupport.recentMessages(List.of(new UserMessage("a😀b")), 1, 2));
        assertEquals(
                List.of(Map.of("role", "user", "text", "new")),
                DecisionSupport.recentMessages(
                        List.of(new UserMessage("old"), new UserMessage("new")), 1, 100));
    }

    private static ModelCallInput route(
            OpenAIDecisionsModelRouterMiddleware router, RuntimeContext ctx, ModelCallInput input) {
        var captured = new AtomicReference<ModelCallInput>();
        router.onModelCall(
                        null,
                        ctx,
                        input,
                        i -> {
                            captured.set(i);
                            return Flux.empty();
                        })
                .blockLast();
        return captured.get();
    }

    private static ReasoningInput select(
            OpenAIDecisionsToolSelectionMiddleware selector, ReasoningInput input) {
        var captured = new AtomicReference<ReasoningInput>();
        var calls = new AtomicInteger();
        selector.onReasoning(
                        null,
                        RuntimeContext.empty(),
                        input,
                        i -> {
                            captured.set(i);
                            calls.incrementAndGet();
                            return Flux.empty();
                        })
                .blockLast();
        assertEquals(1, calls.get());
        return captured.get();
    }

    private static ToolSchema tool(String name) {
        return ToolSchema.builder().name(name).description(name).parameters(Map.of()).build();
    }

    private static ToolUseBlock call(String id, String name) {
        return new ToolUseBlock(id, name, Map.of("command", "echo test"));
    }

    private static DecisionAnswer.Predicate predicate(double value) {
        return new DecisionAnswer.Predicate(value);
    }

    private static DecisionAnswer.Choice choice(String value, double confidence) {
        return new DecisionAnswer.Choice(
                value,
                confidence,
                List.of(
                        new DecisionAnswer.ChoiceProbability("fast", 0.9),
                        new DecisionAnswer.ChoiceProbability("slow", 0.1)));
    }

    private static DecisionResult result(Map<String, DecisionAnswer> answers) {
        return new DecisionResult("fixture", answers, null);
    }

    private static DecisionResult predicates(DecisionRequest request, double value) {
        Map<String, DecisionAnswer> answers = new LinkedHashMap<>();
        request.questions().keySet().forEach(name -> answers.put(name, predicate(value)));
        return result(answers);
    }
}
