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

package io.agentscope.extensions.judge.jev.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link JevAutoModeMiddleware}. Jev is mocked with a stub function; {@code next}
 * is a capturing lambda, so any tool call that does not reach it was blocked by the middleware
 * itself (no permission engine is involved).
 */
class JevAutoModeMiddlewareTest {

    private static final String GUARDED_TOOL = "bash";
    private static final String UNGUARDED_TOOL = "read_file";

    @Test
    void passesThroughWhenNoGuardedToolsArePresent() {
        AgentState state = state();
        RuntimeContext ctx = ctx(state);
        AtomicInteger calls = new AtomicInteger();
        JevAutoModeMiddleware middleware =
                JevAutoModeMiddleware.builder(
                                request -> {
                                    calls.incrementAndGet();
                                    return Mono.error(new IllegalStateException("should not call"));
                                })
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(2),
                                        "test",
                                        (c, r) -> {}))
                        .guardedTool(GUARDED_TOOL)
                        .build();

        AtomicReference<ActingInput> captured = new AtomicReference<>();
        middleware
                .onActing(
                        agent("test"),
                        ctx,
                        new ActingInput(
                                List.of(new ToolUseBlock("id-1", UNGUARDED_TOOL, Map.of()))),
                        next -> {
                            captured.set(next);
                            return Flux.empty();
                        })
                .then()
                .block();

        assertEquals(0, calls.get());
        assertEquals(1, captured.get().toolCalls().size());
        assertEquals(UNGUARDED_TOOL, captured.get().toolCalls().get(0).getName());
    }

    @Test
    void allowsGuardedToolWhenJevAssessmentIsSafe() {
        AgentState state = state();
        RuntimeContext ctx = ctx(state);
        JevAutoModeMiddleware middleware =
                JevAutoModeMiddleware.builder(
                                request -> Mono.just(result(Map.of("tool_0", noul(0.9)))))
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(2),
                                        "test",
                                        (c, r) -> {}))
                        .guardedTool(GUARDED_TOOL)
                        .build();

        AtomicReference<ActingInput> captured = new AtomicReference<>();
        middleware
                .onActing(
                        agent("test"),
                        ctx,
                        new ActingInput(List.of(new ToolUseBlock("id-1", GUARDED_TOOL, Map.of()))),
                        next -> {
                            captured.set(next);
                            return Flux.empty();
                        })
                .then()
                .block();

        assertEquals(1, captured.get().toolCalls().size());
        assertEquals(GUARDED_TOOL, captured.get().toolCalls().get(0).getName());
        assertEquals(0, state.contextMutable().size());
    }

    @Test
    void deniesGuardedToolWhenJevAssessmentIsRisky() {
        AgentState state = state();
        RuntimeContext ctx = ctx(state);
        JevAutoModeMiddleware middleware =
                JevAutoModeMiddleware.builder(
                                request -> Mono.just(result(Map.of("tool_0", noul(0.1)))))
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(2),
                                        "test",
                                        (c, r) -> {}))
                        .guardedTool(GUARDED_TOOL)
                        .build();

        AtomicReference<ActingInput> captured = new AtomicReference<>();
        List<io.agentscope.core.event.AgentEvent> events = new ArrayList<>();
        middleware
                .onActing(
                        agent("test"),
                        ctx,
                        new ActingInput(List.of(new ToolUseBlock("id-1", GUARDED_TOOL, Map.of()))),
                        next -> {
                            captured.set(next);
                            return Flux.empty();
                        })
                .doOnNext(events::add)
                .then()
                .block();

        assertTrue(captured.get() == null || captured.get().toolCalls().isEmpty());

        assertEquals(3, events.size());
        assertTrue(events.get(0) instanceof ToolResultStartEvent);
        assertTrue(events.get(1) instanceof ToolResultTextDeltaEvent);
        assertTrue(events.get(2) instanceof ToolResultEndEvent);
        assertEquals(ToolResultState.DENIED, ((ToolResultEndEvent) events.get(2)).getState());
        assertEquals("id-1", ((ToolResultEndEvent) events.get(2)).getToolCallId());
        assertEquals(GUARDED_TOOL, ((ToolResultEndEvent) events.get(2)).getToolCallName());

        assertEquals(1, state.contextMutable().size());
        Msg deniedMsg = state.contextMutable().get(0);
        ToolResultBlock denied = (ToolResultBlock) deniedMsg.getContent().get(0);
        assertEquals(ToolResultState.DENIED, denied.getState());
        assertEquals("id-1", denied.getId());
        assertEquals(GUARDED_TOOL, denied.getName());
    }

    @Test
    void mixesGuardedAndUnguardedTools() {
        AgentState state = state();
        RuntimeContext ctx = ctx(state);
        JevAutoModeMiddleware middleware =
                JevAutoModeMiddleware.builder(
                                request ->
                                        Mono.just(
                                                result(
                                                        Map.of(
                                                                "tool_0", noul(0.1),
                                                                "tool_1", noul(0.9)))))
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(2),
                                        "test",
                                        (c, r) -> {}))
                        .guardedTool(GUARDED_TOOL)
                        .build();

        AtomicReference<ActingInput> captured = new AtomicReference<>();
        middleware
                .onActing(
                        agent("test"),
                        ctx,
                        new ActingInput(
                                List.of(
                                        new ToolUseBlock("id-1", GUARDED_TOOL, Map.of()),
                                        new ToolUseBlock("id-2", UNGUARDED_TOOL, Map.of()),
                                        new ToolUseBlock("id-3", GUARDED_TOOL, Map.of()))),
                        next -> {
                            captured.set(next);
                            return Flux.empty();
                        })
                .then()
                .block();

        assertEquals(2, captured.get().toolCalls().size());
        assertEquals(UNGUARDED_TOOL, captured.get().toolCalls().get(0).getName());
        assertEquals(GUARDED_TOOL, captured.get().toolCalls().get(1).getName());
        assertEquals("id-3", captured.get().toolCalls().get(1).getId());
    }

    @Test
    void batchesMultipleGuardedToolsIntoOneRequest() {
        AgentState state = state();
        RuntimeContext ctx = ctx(state);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<SystemOneRequest> request = new AtomicReference<>();
        JevAutoModeMiddleware middleware =
                JevAutoModeMiddleware.builder(
                                req -> {
                                    calls.incrementAndGet();
                                    request.set(req);
                                    return Mono.just(
                                            result(
                                                    Map.of(
                                                            "tool_0", noul(0.9),
                                                            "tool_1", noul(0.1))));
                                })
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(2),
                                        "test",
                                        (c, r) -> {}))
                        .guardedTool(GUARDED_TOOL)
                        .build();

        middleware
                .onActing(
                        agent("test"),
                        ctx,
                        new ActingInput(
                                List.of(
                                        new ToolUseBlock("id-1", GUARDED_TOOL, Map.of()),
                                        new ToolUseBlock("id-2", GUARDED_TOOL, Map.of()))),
                        next -> Flux.empty())
                .then()
                .block();

        assertEquals(1, calls.get());
        assertEquals(2, request.get().questions().size());
        assertTrue(request.get().questions().containsKey("tool_0"));
        assertTrue(request.get().questions().containsKey("tool_1"));

        Map<?, ?> requestState = (Map<?, ?>) request.get().state();
        assertTrue(requestState.containsKey("messages"));
        assertTrue(requestState.containsKey("tool_calls"));
        Map<?, ?> toolCalls = (Map<?, ?>) requestState.get("tool_calls");
        assertEquals(2, toolCalls.size());
    }

    @Test
    void skipsAlreadyAllowedToolCalls() {
        AgentState state = state();
        RuntimeContext ctx = ctx(state);
        AtomicInteger calls = new AtomicInteger();
        JevAutoModeMiddleware middleware =
                JevAutoModeMiddleware.builder(
                                request -> {
                                    calls.incrementAndGet();
                                    return Mono.error(new IllegalStateException("should not call"));
                                })
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(2),
                                        "test",
                                        (c, r) -> {}))
                        .guardedTool(GUARDED_TOOL)
                        .build();

        ToolUseBlock allowed =
                new ToolUseBlock("id-1", GUARDED_TOOL, Map.of())
                        .withState(io.agentscope.core.message.ToolCallState.ALLOWED);
        AtomicReference<ActingInput> captured = new AtomicReference<>();
        middleware
                .onActing(
                        agent("test"),
                        ctx,
                        new ActingInput(List.of(allowed)),
                        next -> {
                            captured.set(next);
                            return Flux.empty();
                        })
                .then()
                .block();

        assertEquals(0, calls.get());
        assertEquals(1, captured.get().toolCalls().size());
        assertEquals(GUARDED_TOOL, captured.get().toolCalls().get(0).getName());
    }

    @Test
    void errorBlocksGuardedCalls() {
        AgentState state = state();
        RuntimeContext ctx = ctx(state);
        JevAutoModeMiddleware middleware =
                JevAutoModeMiddleware.builder(
                                request -> Mono.error(new IllegalStateException("offline")))
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(2),
                                        "test",
                                        (c, r) -> {}))
                        .guardedTool(GUARDED_TOOL)
                        .build();

        AtomicReference<ActingInput> captured = new AtomicReference<>();
        middleware
                .onActing(
                        agent("test"),
                        ctx,
                        new ActingInput(List.of(new ToolUseBlock("id-1", GUARDED_TOOL, Map.of()))),
                        next -> {
                            captured.set(next);
                            return Flux.empty();
                        })
                .then()
                .block();

        assertEquals(null, captured.get());
    }

    @Test
    void canFailClosed() {
        AgentState state = state();
        RuntimeContext ctx = ctx(state);
        JevAutoModeMiddleware middleware =
                JevAutoModeMiddleware.builder(
                                request -> Mono.error(new IllegalStateException("offline")))
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(2),
                                        "test",
                                        (c, r) -> {}))
                        .guardedTool(GUARDED_TOOL)
                        .build();

        AtomicReference<Throwable> error = new AtomicReference<>();
        middleware
                .onActing(
                        agent("test"),
                        ctx,
                        new ActingInput(List.of(new ToolUseBlock("id-1", GUARDED_TOOL, Map.of()))),
                        next -> Flux.empty())
                .doOnError(error::set)
                .then()
                .onErrorComplete()
                .block();

        assertEquals(null, error.get());
        assertEquals(1, state.contextMutable().size());
    }

    @Test
    void missingStateBlocksGuardedCalls() {
        RuntimeContext ctx = RuntimeContext.empty();
        AtomicInteger calls = new AtomicInteger();
        JevAutoModeMiddleware middleware =
                JevAutoModeMiddleware.builder(
                                request -> {
                                    calls.incrementAndGet();
                                    return Mono.error(new IllegalStateException("should not call"));
                                })
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(2),
                                        "test",
                                        (c, r) -> {}))
                        .guardedTool(GUARDED_TOOL)
                        .build();

        AtomicReference<ActingInput> captured = new AtomicReference<>();
        middleware
                .onActing(
                        agent("test"),
                        ctx,
                        new ActingInput(List.of(new ToolUseBlock("id-1", GUARDED_TOOL, Map.of()))),
                        next -> {
                            captured.set(next);
                            return Flux.empty();
                        })
                .then()
                .block();

        assertEquals(0, calls.get());
        assertEquals(null, captured.get());
    }

    @Test
    void usesSafetyThreshold() {
        AgentState state = state();
        RuntimeContext ctx = ctx(state);
        JevAutoModeMiddleware middleware =
                JevAutoModeMiddleware.builder(
                                request -> Mono.just(result(Map.of("tool_0", noul(0.7)))))
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(2),
                                        "test",
                                        (c, r) -> {}))
                        .guardedTool(GUARDED_TOOL)
                        .safetyThreshold(0.8)
                        .build();

        AtomicReference<ActingInput> captured = new AtomicReference<>();
        middleware
                .onActing(
                        agent("test"),
                        ctx,
                        new ActingInput(List.of(new ToolUseBlock("id-1", GUARDED_TOOL, Map.of()))),
                        next -> {
                            captured.set(next);
                            return Flux.empty();
                        })
                .then()
                .block();

        assertTrue(captured.get() == null || captured.get().toolCalls().isEmpty());
        assertEquals(1, state.contextMutable().size());
    }

    @Test
    void treatsUnexpectedAnswerAsDenied() {
        AgentState state = state();
        RuntimeContext ctx = ctx(state);
        JevAutoModeMiddleware middleware =
                JevAutoModeMiddleware.builder(
                                request ->
                                        Mono.just(
                                                result(
                                                        Map.of(
                                                                "tool_0",
                                                                new io.agentscope.extensions.judge
                                                                        .jev.ChoiceAnswer(
                                                                        "unexpected",
                                                                        Map.of(),
                                                                        0.5)))))
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(2),
                                        "test",
                                        (c, r) -> {}))
                        .guardedTool(GUARDED_TOOL)
                        .build();

        AtomicReference<ActingInput> captured = new AtomicReference<>();
        middleware
                .onActing(
                        agent("test"),
                        ctx,
                        new ActingInput(List.of(new ToolUseBlock("id-1", GUARDED_TOOL, Map.of()))),
                        next -> {
                            captured.set(next);
                            return Flux.empty();
                        })
                .then()
                .block();

        assertEquals(null, captured.get());
        assertEquals(1, state.contextMutable().size());
    }

    @Test
    void validatesGuardedToolsAreNotEmpty() {
        IllegalArgumentException error =
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                JevAutoModeMiddleware.builder(
                                                request -> Mono.just(result(Map.of())))
                                        .guardedTools(Set.of())
                                        .build());
        assertEquals("guardedTools must not be empty", error.getMessage());
    }

    @Test
    void shadowNeverChangesCallsOrWritesDeniedResults() {
        var state = state();
        var input = new ActingInput(List.of(new ToolUseBlock("a", GUARDED_TOOL, Map.of())));
        var middleware =
                JevAutoModeMiddleware.builder(
                                r -> Mono.just(result(Map.of("tool_0", new NoulAnswer(0.01)))))
                        .guardedTool(GUARDED_TOOL)
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode.SHADOW,
                                        java.time.Duration.ofSeconds(1),
                                        "v1",
                                        (c, r) -> {}))
                        .build();
        middleware
                .onActing(
                        null,
                        ctx(state),
                        input,
                        i -> {
                            assertEquals(input, i);
                            return Flux.empty();
                        })
                .blockLast();
        assertEquals(0, state.contextMutable().size());
    }

    @Test
    void cancelledAssessmentDoesNotDispatchOrWriteResults() {
        var state = state();
        var dispatches = new AtomicInteger();
        var middleware =
                JevAutoModeMiddleware.builder(r -> Mono.never())
                        .guardedTool(GUARDED_TOOL)
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(1),
                                        "v1",
                                        (c, r) -> {}))
                        .build();
        reactor.test.StepVerifier.create(
                        middleware.onActing(
                                null,
                                ctx(state),
                                new ActingInput(
                                        List.of(new ToolUseBlock("a", GUARDED_TOOL, Map.of()))),
                                i -> {
                                    dispatches.incrementAndGet();
                                    return Flux.empty();
                                }))
                .thenCancel()
                .verify();
        assertEquals(0, dispatches.get());
        assertEquals(0, state.contextMutable().size());
    }

    @Test
    void changedConfirmedParametersRequireReviewEvenWithAllowedMarker() {
        var state = state();
        var ctx = ctx(state);
        var original = new ToolUseBlock("id", GUARDED_TOOL, Map.of("amount", 1));
        io.agentscope.extensions.judge.jev.JevConfirmedCalls.remember(ctx, original);
        var changed =
                new ToolUseBlock("id", GUARDED_TOOL, Map.of("amount", 100))
                        .withState(ToolCallState.ALLOWED);
        var middleware =
                JevAutoModeMiddleware.builder(
                                r -> {
                                    throw new AssertionError(
                                            "changed confirmation must not be overridden by JEV");
                                })
                        .guardedTool(GUARDED_TOOL)
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(1),
                                        "v1",
                                        (c, r) -> {}))
                        .build();
        middleware
                .onActing(
                        null,
                        ctx,
                        new ActingInput(List.of(changed)),
                        i -> {
                            throw new AssertionError("must not dispatch");
                        })
                .blockLast();
        assertEquals(1, state.contextMutable().size());
    }

    private static AgentState state() {
        return AgentState.builder().replyId("reply-123").build();
    }

    private static RuntimeContext ctx(AgentState state) {
        RuntimeContext ctx = RuntimeContext.empty();
        ctx.setAgentState(state);
        return ctx;
    }

    private static Agent agent(String name) {
        return new StubAgent(name);
    }

    /** Minimal Agent stub exposing only name. */
    private static final class StubAgent implements Agent {
        private final String name;

        StubAgent(String name) {
            this.name = name;
        }

        @Override
        public String getAgentId() {
            return "id-" + name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void interrupt() {}

        @Override
        public void interrupt(Msg msg) {}

        @Override
        public Mono<Msg> call(List<Msg> msgs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Void> observe(Msg msg) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> observe(List<Msg> msgs) {
            return Mono.empty();
        }
    }

    private static NoulAnswer noul(double value) {
        return new NoulAnswer(value);
    }

    private static SystemOneResult result(Map<String, Answer> answers) {
        return new SystemOneResult("jev-test", answers, new Usage(1, 1));
    }
}
