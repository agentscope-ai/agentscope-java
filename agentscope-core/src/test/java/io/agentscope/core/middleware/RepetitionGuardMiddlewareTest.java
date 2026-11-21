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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RunOutcome;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.SchemaOnlyTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolRequestConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class RepetitionGuardMiddlewareTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final class Fixture {
        final AtomicInteger executions = new AtomicInteger();
        final AtomicInteger turns = new AtomicInteger();
        IntFunction<List<ToolUseBlock>> requests =
                n ->
                        n <= 4
                                ? List.of(call("call-" + n, "lookup", Map.of("key", "same")))
                                : List.of();
        Supplier<PermissionDecision> permission = () -> PermissionDecision.allow("test");
        final MockModel model =
                new MockModel(
                        messages -> {
                            var calls = requests.apply(turns.incrementAndGet());
                            return List.of(
                                    ChatResponse.builder()
                                            .content(
                                                    calls.isEmpty()
                                                            ? List.of(
                                                                    TextBlock.builder()
                                                                            .text("summary")
                                                                            .build())
                                                            : new ArrayList<>(calls))
                                            .build());
                        });

        ReActAgent.Builder builder() {
            Toolkit toolkit = new Toolkit();
            toolkit.registerAgentTool(
                    new ToolBase(
                            ToolBase.builder()
                                    .name("lookup")
                                    .description("Return the same value")
                                    .inputSchema(Map.of("type", "object"))
                                    .readOnly(true)) {
                        @Override
                        public Mono<PermissionDecision> checkPermissions(
                                Map<String, Object> input, PermissionContextState context) {
                            return Mono.just(permission.get());
                        }

                        @Override
                        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                            executions.incrementAndGet();
                            return Mono.just(ToolResultBlock.success("unchanged"));
                        }
                    });
            return ReActAgent.builder().name("assistant").model(model).toolkit(toolkit).maxIters(4);
        }
    }

    private static Msg invoke(ReActAgent agent, boolean streaming) {
        return streaming
                ? agent.streamEvents(List.of())
                        .ofType(AgentResultEvent.class)
                        .single()
                        .map(AgentResultEvent::getResult)
                        .block(TIMEOUT)
                : agent.call(List.of()).block(TIMEOUT);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void withoutGuardIdenticalCallsRunUntilTheIterationLimit(boolean streaming) {
        Fixture f = new Fixture();
        Msg result = invoke(f.builder().build(), streaming);
        assertEquals(4, f.executions.get());
        assertEquals(GenerateReason.MAX_ITERATIONS, result.getGenerateReason());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void warnsOnceAndClosesBlockedToolCallWithoutDispatch(boolean streaming) {
        Fixture f = new Fixture();
        var agent = f.builder().middleware(new RepetitionGuardMiddleware(2, 3, Set.of())).build();
        Msg result = invoke(agent, streaming);
        assertEquals(2, f.executions.get());
        assertEquals(3, f.turns.get());
        assertEquals(GenerateReason.REPEATED_TOOL_CALL, result.getGenerateReason());
        assertEquals(RunOutcome.Status.FAILED, RunOutcome.fromResult(result).status());
        assertFalse(hasReminder(f.model.getRecordedMessages().get(1)));
        assertTrue(hasReminder(f.model.getRecordedMessages().get(2)));
        var history = agent.getAgentState().getContext();
        assertFalse(hasReminder(history));
        var receipts =
                history.stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .toList();
        assertEquals(
                List.of("call-1", "call-2", "call-3"),
                receipts.stream().map(ToolResultBlock::getId).toList());
        assertEquals(ToolResultState.ERROR, receipts.get(2).getState());
    }

    private static ToolUseBlock call(String id, String name, Map<String, Object> args) {
        return ToolUseBlock.builder()
                .id(id)
                .name(name)
                .input(args)
                .content(JsonUtils.getJsonCodec().toJson(args))
                .build();
    }

    @Test
    void nestedObjectOrderDoesNotMatterAndOffendingBatchIsNotPartiallyDispatched() {
        Fixture f = new Fixture();
        Map<String, Object> forward = new LinkedHashMap<>();
        forward.put("x", 1);
        forward.put("y", 2);
        Map<String, Object> reverse = new LinkedHashMap<>();
        reverse.put("y", 2);
        reverse.put("x", 1);
        f.requests =
                n ->
                        n == 1
                                ? List.of(call("a", "lookup", Map.of("args", forward)))
                                : List.of(
                                        call("b", "lookup", Map.of("args", reverse)),
                                        call("c", "lookup", Map.of("args", forward)));
        var agent = f.builder().middleware(new RepetitionGuardMiddleware(2, 3, Set.of())).build();
        assertEquals(GenerateReason.REPEATED_TOOL_CALL, invoke(agent, true).getGenerateReason());
        assertEquals(1, f.executions.get());
        var receipts =
                agent.getAgentState().getContext().stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .toList();
        assertEquals(
                List.of("a", "b", "c"), receipts.stream().map(ToolResultBlock::getId).toList());
        assertEquals(
                List.of(ToolResultState.SUCCESS, ToolResultState.ERROR, ToolResultState.ERROR),
                receipts.stream().map(ToolResultBlock::getState).toList());
    }

    @Test
    void changedArrayOrderResetsStreak() {
        Fixture f = new Fixture();
        f.requests =
                n ->
                        n <= 3
                                ? List.of(
                                        call(
                                                "call-" + n,
                                                "lookup",
                                                Map.of(
                                                        "items",
                                                        n == 2 ? List.of(2, 1) : List.of(1, 2))))
                                : List.of();
        var agent = f.builder().middleware(new RepetitionGuardMiddleware(1, 2, Set.of())).build();
        assertEquals(GenerateReason.MODEL_STOP, invoke(agent, false).getGenerateReason());
        assertEquals(3, f.executions.get());
    }

    @Test
    void excludedPollingToolDoesNotWarnOrStop() {
        Fixture f = new Fixture();
        var agent =
                f.builder()
                        .middleware(new RepetitionGuardMiddleware(1, 2, Set.of("lookup")))
                        .build();
        assertEquals(GenerateReason.MAX_ITERATIONS, invoke(agent, false).getGenerateReason());
        assertEquals(4, f.executions.get());
        assertTrue(
                f.model.getRecordedMessages().stream()
                        .noneMatch(RepetitionGuardMiddlewareTest::hasReminder));
    }

    @Test
    void permissionPromptWinsEvenWhenTheNextCallWouldReachTheLimit() {
        Fixture f = new Fixture();
        f.permission =
                () ->
                        f.executions.get() == 0
                                ? PermissionDecision.allow("test")
                                : PermissionDecision.ask("confirm");
        var agent = f.builder().middleware(new RepetitionGuardMiddleware(1, 2, Set.of())).build();
        assertEquals(GenerateReason.PERMISSION_ASKING, invoke(agent, true).getGenerateReason());
        assertEquals(1, f.executions.get());
    }

    private static Flux<AgentEvent> dispatchProbe(
            RepetitionGuardMiddleware guard, RuntimeContext ctx, String tool) {
        var calls = List.of(call("local", tool, Map.of()));
        return guard.onActing(
                null,
                ctx,
                new ActingInput(calls),
                acting ->
                        Flux.deferContextual(
                                view ->
                                        Flux.just(
                                                new AgentStartEvent(
                                                        null,
                                                        RepetitionGuardMiddleware.stopsDispatch(
                                                                        view,
                                                                        ctx,
                                                                        acting.toolCalls(),
                                                                        acting.toolCalls())
                                                                ? "stop"
                                                                : "run",
                                                        tool))));
    }

    @Test
    void differentToolBreaksStreak() {
        var guard = new RepetitionGuardMiddleware(1, 2, Set.of());
        var ctx = RuntimeContext.empty();
        var flow =
                guard.onAgent(
                        null,
                        ctx,
                        new AgentInput(List.of()),
                        input ->
                                Flux.concat(
                                        dispatchProbe(guard, ctx, "a"),
                                        dispatchProbe(guard, ctx, "b"),
                                        dispatchProbe(guard, ctx, "a"),
                                        dispatchProbe(guard, ctx, "a")));
        StepVerifier.create(flow.cast(AgentStartEvent.class).map(AgentStartEvent::getReplyId))
                .expectNext("run", "run", "run", "stop")
                .expectComplete()
                .verify(TIMEOUT);
    }

    @Test
    void subscriptionsStayIsolatedAcrossOverlapErrorCancellationAndResubscription() {
        var guard = new RepetitionGuardMiddleware(1, 2, Set.of());
        var ctx = RuntimeContext.empty();
        Sinks.Empty<Void> resume = Sinks.empty();
        var first =
                guard.onAgent(
                        null,
                        ctx,
                        new AgentInput(List.of()),
                        input ->
                                dispatchProbe(guard, ctx, "a")
                                        .concatWith(
                                                resume.asMono()
                                                        .thenMany(dispatchProbe(guard, ctx, "a"))));
        var failing =
                guard.onAgent(
                        null,
                        ctx,
                        new AgentInput(List.of()),
                        input ->
                                dispatchProbe(guard, ctx, "a")
                                        .concatWith(
                                                Flux.error(new IllegalStateException("fixture"))));
        StepVerifier.create(first.cast(AgentStartEvent.class).map(AgentStartEvent::getReplyId))
                .expectNext("run")
                .then(
                        () -> {
                            StepVerifier.create(
                                            failing.cast(AgentStartEvent.class)
                                                    .map(AgentStartEvent::getReplyId))
                                    .expectNext("run")
                                    .expectErrorMessage("fixture")
                                    .verify(TIMEOUT);
                            StepVerifier.create(
                                            first.cast(AgentStartEvent.class)
                                                    .map(AgentStartEvent::getReplyId))
                                    .expectNext("run")
                                    .thenCancel()
                                    .verify(TIMEOUT);
                            resume.tryEmitEmpty().orThrow();
                        })
                .expectNext("stop")
                .expectComplete()
                .verify(TIMEOUT);
        StepVerifier.create(first.cast(AgentStartEvent.class).map(AgentStartEvent::getReplyId))
                .expectNext("run", "stop")
                .expectComplete()
                .verify(TIMEOUT);
    }

    @Test
    void identicalExternalCallsStillRequestExternalExecution() {
        Fixture f = new Fixture();
        f.requests = n -> List.of(call("a", "lookup", Map.of()), call("b", "lookup", Map.of()));
        var agent = f.builder().middleware(new RepetitionGuardMiddleware(1, 2, Set.of())).build();
        var ctx =
                RuntimeContext.builder()
                        .toolRequestConfig(
                                new ToolRequestConfig(
                                        Map.of(
                                                "lookup",
                                                new SchemaOnlyTool(
                                                        "lookup",
                                                        "external",
                                                        Map.of("type", "object"))),
                                        null))
                        .build();
        var result =
                agent.streamEvents(List.of(), ctx)
                        .ofType(AgentResultEvent.class)
                        .single()
                        .block(TIMEOUT);
        assertEquals(GenerateReason.TOOL_SUSPENDED, result.getResult().getGenerateReason());
        assertEquals(0, f.executions.get());
    }

    @Test
    void nextTurnDoesNotReplayABlockedCallOrInheritItsCount() {
        Fixture f = new Fixture();
        var agent = f.builder().middleware(new RepetitionGuardMiddleware(1, 2, Set.of())).build();
        assertEquals(GenerateReason.REPEATED_TOOL_CALL, invoke(agent, false).getGenerateReason());
        assertEquals(1, f.executions.get());
        f.requests =
                n -> n == 3 ? List.of(call("new", "lookup", Map.of("key", "same"))) : List.of();
        assertEquals(GenerateReason.MODEL_STOP, invoke(agent, true).getGenerateReason());
        assertEquals(2, f.executions.get());
    }

    private static boolean hasReminder(List<Msg> messages) {
        return messages.stream()
                .anyMatch(
                        m ->
                                m.getMetadata() != null
                                        && "repeated_tool_call"
                                                .equals(
                                                        m.getMetadata()
                                                                .get(Msg.METADATA_REMINDER_KIND)));
    }
}
