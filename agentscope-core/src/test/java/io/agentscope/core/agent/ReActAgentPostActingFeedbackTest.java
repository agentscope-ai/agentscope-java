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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolSuspendException;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Verifies final feedback at the next model request, rather than only event setter fields. */
@SuppressWarnings("deprecation")
class ReActAgentPostActingFeedbackTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacedBlockReachesNextModelRequestAndHistory(boolean streaming) {
        MockModel model = toolThenDoneModel();
        ReActAgent agent =
                builder(model)
                        .hook(
                                hook(
                                        event ->
                                                event.setToolResult(
                                                        ToolResultBlock.error("corrective feedback")
                                                                .withIdAndName(
                                                                        "other-id", "other-name"))))
                        .build();

        Msg result = invoke(agent, streaming);

        assertNotNull(result);
        assertEquals(2, model.getRecordedMessages().size());
        assertFeedback(
                toolResult(model.getRecordedMessages().get(1)),
                "Error: corrective feedback",
                ToolResultState.ERROR);
        assertFeedback(
                toolResult(agent.getAgentState().getContext()),
                "Error: corrective feedback",
                ToolResultState.ERROR);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitlyReplacedMessageTakesPrecedence(boolean streaming) {
        MockModel model = toolThenDoneModel();
        ReActAgent agent =
                builder(model)
                        .hook(
                                hook(
                                        event -> {
                                            event.setToolResultMsg(
                                                    event.getToolResultMsg()
                                                            .withContent(
                                                                    List.of(
                                                                            ToolResultBlock.success(
                                                                                            "explicit"
                                                                                                + " message")
                                                                                    .withIdAndName(
                                                                                            "tc1",
                                                                                            "lookup")))
                                                            .withMetadata(
                                                                    Map.of(
                                                                            "feedback-source",
                                                                            "explicit")));
                                            event.setToolResult(
                                                    ToolResultBlock.success("block replacement"));
                                        }))
                        .build();

        invoke(agent, streaming);

        assertEquals(2, model.getRecordedMessages().size());
        assertFeedback(
                toolResult(model.getRecordedMessages().get(1)),
                "explicit message",
                ToolResultState.SUCCESS);
        Msg resultMessage =
                model.getRecordedMessages().get(1).stream()
                        .filter(
                                message ->
                                        !message.getContentBlocks(ToolResultBlock.class).isEmpty())
                        .findFirst()
                        .orElseThrow();
        assertEquals("explicit", resultMessage.getMetadata().get("feedback-source"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopReturnsAndPersistsReplacementWithoutAnotherModelCall(boolean streaming) {
        MockModel model = toolThenDoneModel();
        ReActAgent agent =
                builder(model)
                        .hook(
                                hook(
                                        event -> {
                                            event.setToolResult(
                                                    ToolResultBlock.success("review me"));
                                            event.stopAgent();
                                        }))
                        .build();

        Msg result = invoke(agent, streaming);

        assertNotNull(result);
        assertEquals(GenerateReason.ACTING_STOP_REQUESTED, result.getGenerateReason());
        assertEquals(1, model.getRecordedMessages().size());
        assertFeedback(toolResult(List.of(result)), "review me", ToolResultState.SUCCESS);
        assertFeedback(
                toolResult(agent.getAgentState().getContext()),
                "review me",
                ToolResultState.SUCCESS);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void modelCallFeedbackCanBeProjectedWithoutChangingHistory(boolean streaming) {
        MockModel model = toolThenDoneModel();
        MiddlewareBase feedback =
                new MiddlewareBase() {
                    @Override
                    public Set<ExtensionPoint> activePoints() {
                        return EnumSet.of(ExtensionPoint.ON_MODEL_CALL);
                    }

                    @Override
                    public Flux<AgentEvent> onModelCall(
                            Agent agent,
                            RuntimeContext context,
                            ModelCallInput input,
                            Function<ModelCallInput, Flux<AgentEvent>> next) {
                        List<Msg> projected =
                                input.messages().stream()
                                        .map(
                                                message -> {
                                                    if (message.getContentBlocks(
                                                                    ToolResultBlock.class)
                                                            .isEmpty()) {
                                                        return message;
                                                    }
                                                    ToolResultBlock original =
                                                            message.getContentBlocks(
                                                                            ToolResultBlock.class)
                                                                    .get(0);
                                                    return message.withContent(
                                                            List.of(
                                                                    ToolResultBlock.success(
                                                                                    "request"
                                                                                        + " feedback")
                                                                            .withIdAndName(
                                                                                    original
                                                                                            .getId(),
                                                                                    original
                                                                                            .getName())));
                                                })
                                        .toList();
                        return next.apply(
                                new ModelCallInput(
                                        projected, input.tools(), input.options(), input.model()));
                    }
                };
        ReActAgent agent = builder(model).middleware(feedback).build();

        invoke(agent, streaming);

        assertEquals(2, model.getRecordedMessages().size());
        assertFeedback(
                toolResult(model.getRecordedMessages().get(1)),
                "request feedback",
                ToolResultState.SUCCESS);
        assertFeedback(
                toolResult(agent.getAgentState().getContext()),
                "raw output",
                ToolResultState.SUCCESS);
        assertEquals(
                model.getRecordedMessages().get(0).get(0).getTextContent(),
                model.getRecordedMessages().get(1).get(0).getTextContent());
        assertFalse(
                model.getRecordedMessages().get(0).stream()
                        .anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void middlewareStopPersistsTheWholeExecutedBatchInCallOrder(boolean streaming) {
        MockModel model =
                batchModel(
                        List.of(
                                call("first", "fixture"),
                                call("missing", "absent"),
                                call("last", "fixture")));
        AtomicInteger executions = new AtomicInteger();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(
                fixture(
                        executions,
                        p ->
                                Mono.just(
                                        ToolResultBlock.success(
                                                "output-" + p.getToolUseBlock().getId()))));
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        RuntimeContext ctx = RuntimeContext.builder().userId("u").sessionId("acting-stop").build();
        List<String> hooks = new ArrayList<>();
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("assistant")
                        .model(model)
                        .toolkit(toolkit)
                        .stateStore(store)
                        .middleware(stopAfterActing())
                        .hook(
                                hook(
                                        event -> {
                                            hooks.add(event.getToolUse().getId());
                                            event.stopAgent(); // Must not cancel later
                                            // hooks/writes or override the
                                            // reason.
                                        }))
                        .build()) {
            Msg result = invokeRun(agent, ctx, streaming);
            assertEquals(GenerateReason.MIDDLEWARE_STOP_REQUESTED, result.getGenerateReason());
            assertEquals(List.of("first", "missing", "last"), hooks);
            List<ToolResultBlock> persisted = allResults(agent.getAgentState(ctx).getContext());
            assertEquals(
                    List.of("first", "missing", "last"),
                    persisted.stream().map(ToolResultBlock::getId).toList());
            assertEquals(
                    List.of(
                            ToolResultState.SUCCESS,
                            ToolResultState.ERROR,
                            ToolResultState.SUCCESS),
                    persisted.stream().map(ToolResultBlock::getState).toList());
            assertEquals(
                    "output-first", ((TextBlock) persisted.get(0).getOutput().get(0)).getText());
            assertEquals(
                    "Error: Tool not found: absent",
                    ((TextBlock) persisted.get(1).getOutput().get(0)).getText());
            assertEquals(
                    "output-last", ((TextBlock) persisted.get(2).getOutput().get(0)).getText());
            assertEquals(3, result.getContentBlocks(ToolResultBlock.class).size());
            assertEquals(1, model.getCallCount(), "no extra reasoning or retry on stop");
            assertEquals(2, executions.get());
        }
        // A new agent loads the persisted slot, not the previous agent's in-memory context.
        try (ReActAgent restored =
                ReActAgent.builder()
                        .name("assistant")
                        .model(model)
                        .toolkit(toolkit)
                        .stateStore(store)
                        .build()) {
            List<Msg> loaded = restored.getAgentState(ctx).getContext();
            assertEquals(
                    List.of("first", "missing", "last"),
                    allResults(loaded).stream().map(ToolResultBlock::getId).toList());
            assertTrue(
                    io.agentscope.core.util.MessageUtils.extractPendingToolCalls(
                                    loaded, "assistant")
                            .isEmpty());
            assertEquals(2, executions.get(), "loading state must not repeat side effects");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void middlewareStopReturnsPostHookFactsIncludingMessageRewritesAndNormalizedState(
            boolean streaming) {
        MockModel model =
                batchModel(
                        List.of(
                                call("block", "fixture"),
                                call("message", "fixture"),
                                call("running", "fixture")));
        AtomicInteger executions = new AtomicInteger();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(
                fixture(executions, p -> Mono.just(ToolResultBlock.text("raw output"))));
        List<String> hooks = new ArrayList<>();
        RuntimeContext ctx = RuntimeContext.builder().sessionId("rewritten-stop").build();
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("assistant")
                        .model(model)
                        .toolkit(toolkit)
                        .middleware(stopAfterActing())
                        .hook(
                                hook(
                                        event -> {
                                            String id = event.getToolUse().getId();
                                            hooks.add(id);
                                            if ("block".equals(id)) {
                                                event.setToolResult(
                                                        ToolResultBlock.error("rewritten failure"));
                                                event.stopAgent();
                                            } else if ("message".equals(id)) {
                                                event.setToolResultMsg(
                                                        event.getToolResultMsg()
                                                                .withContent(
                                                                        List.of(
                                                                                TextBlock.builder()
                                                                                        .text(
                                                                                                "message-only"
                                                                                                    + " annotation")
                                                                                        .build(),
                                                                                ToolResultBlock
                                                                                        .error(
                                                                                                "explicit"
                                                                                                    + " message")
                                                                                        .withIdAndName(
                                                                                                id,
                                                                                                "fixture"))));
                                                event.setToolResult(
                                                        ToolResultBlock.success(
                                                                "ignored block rewrite"));
                                            }
                                        }))
                        .build()) {
            Msg result;
            if (streaming) {
                List<AgentEvent> events =
                        agent.streamEvents(List.of(), ctx)
                                .collectList()
                                .block(Duration.ofSeconds(10));
                assertNotNull(events);
                result =
                        events.stream()
                                .filter(AgentResultEvent.class::isInstance)
                                .map(AgentResultEvent.class::cast)
                                .map(AgentResultEvent::getResult)
                                .findFirst()
                                .orElseThrow();
                assertTrue(
                        events.get(events.size() - 1)
                                instanceof io.agentscope.core.event.AgentEndEvent);
            } else {
                result = agent.call(List.of(), ctx).block(Duration.ofSeconds(10));
            }
            assertNotNull(result);
            assertEquals(GenerateReason.MIDDLEWARE_STOP_REQUESTED, result.getGenerateReason());
            assertEquals(
                    List.of("block", "message", "running"),
                    hooks,
                    "an early post-hook stop does not truncate completed siblings");
            List<Msg> written =
                    agent.getAgentState(ctx).getContext().stream()
                            .filter(m -> m.hasContentBlocks(ToolResultBlock.class))
                            .toList();
            assertEquals(3, written.size());
            List<io.agentscope.core.message.ContentBlock> expected = new ArrayList<>();
            for (int i = 0; i < written.size(); i++) {
                expected.add(result.getContentBlocks(ToolUseBlock.class).get(i));
                expected.addAll(written.get(i).getContent());
            }
            assertEquals(
                    expected,
                    result.getContent(),
                    "terminal carries the effective messages written to history, not raw blocks");
            assertEquals(
                    List.of(ToolResultState.ERROR, ToolResultState.ERROR, ToolResultState.SUCCESS),
                    allResults(written).stream().map(ToolResultBlock::getState).toList());
            assertEquals(
                    "Error: rewritten failure",
                    ((TextBlock) allResults(written).get(0).getOutput().get(0)).getText());
            assertEquals(
                    "Error: explicit message",
                    ((TextBlock) allResults(written).get(1).getOutput().get(0)).getText());
            assertEquals(
                    "raw output",
                    ((TextBlock) allResults(written).get(2).getOutput().get(0)).getText());
            assertEquals(3, executions.get());
            assertEquals(1, model.getCallCount());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopOnResultEventKeepsCompletedFactsBeforeBatchSettlement(boolean streaming) {
        MockModel model =
                batchModel(List.of(call("completed", "fixture"), call("unfinished", "fixture")));
        AtomicInteger invocations = new AtomicInteger();
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder().parallel(false).build());
        toolkit.registerAgentTool(
                fixture(
                        invocations,
                        p ->
                                "completed".equals(p.getToolUseBlock().getId())
                                        ? Mono.just(ToolResultBlock.error("completed failure"))
                                        : Mono.never()));
        MiddlewareBase earlyStop =
                new MiddlewareBase() {
                    @Override
                    public Set<ExtensionPoint> activePoints() {
                        return Set.of(ExtensionPoint.ON_ACTING);
                    }

                    @Override
                    public Flux<AgentEvent> onActing(
                            Agent agent,
                            RuntimeContext ctx,
                            ActingInput input,
                            Function<ActingInput, Flux<AgentEvent>> next) {
                        return next.apply(input)
                                .takeUntil(ToolResultEndEvent.class::isInstance)
                                .concatWith(
                                        Flux.just(
                                                new RequestStopEvent(
                                                        "stop on first result",
                                                        GenerateReason.MIDDLEWARE_STOP_REQUESTED)));
                    }
                };
        List<String> hooks = new ArrayList<>();
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        RuntimeContext ctx = RuntimeContext.builder().sessionId("early-stop").build();
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("assistant")
                        .model(model)
                        .toolkit(toolkit)
                        .stateStore(store)
                        .middleware(earlyStop)
                        .hook(hook(event -> hooks.add(event.getToolUse().getId())))
                        .build()) {
            Msg result = invokeRun(agent, ctx, streaming);
            assertEquals(GenerateReason.MIDDLEWARE_STOP_REQUESTED, result.getGenerateReason());
            assertEquals(List.of("completed"), hooks);
            List<ToolResultBlock> facts = allResults(agent.getAgentState(ctx).getContext());
            assertEquals(1, facts.size());
            assertEquals("completed", facts.get(0).getId());
            assertEquals(ToolResultState.ERROR, facts.get(0).getState());
            assertEquals(
                    "Error: completed failure",
                    ((TextBlock) facts.get(0).getOutput().get(0)).getText());
            assertEquals(1, result.getContentBlocks(ToolResultBlock.class).size());
            assertTrue(invocations.get() <= 2, "no invocation is retried");
            assertEquals(1, model.getCallCount());
        }
        try (ReActAgent restored =
                ReActAgent.builder()
                        .name("assistant")
                        .model(model)
                        .toolkit(toolkit)
                        .stateStore(store)
                        .build()) {
            List<Msg> loaded = restored.getAgentState(ctx).getContext();
            assertEquals(
                    List.of("completed"),
                    allResults(loaded).stream().map(ToolResultBlock::getId).toList());
            assertEquals(
                    List.of("unfinished"),
                    io.agentscope.core.util.MessageUtils.extractPendingToolCalls(
                                    loaded, "assistant")
                            .stream()
                            .map(ToolUseBlock::getId)
                            .toList());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void middlewareStopDoesNotConvertErrorsOrSuspensionsIntoSuccess(boolean streaming) {
        for (boolean stop : List.of(false, true)) {
            MockModel model =
                    batchModel(
                            List.of(
                                    call("success", "fixture"),
                                    call("error", "fixture"),
                                    call("pending", "fixture")));
            AtomicInteger executions = new AtomicInteger();
            Toolkit toolkit = new Toolkit();
            toolkit.registerAgentTool(
                    fixture(
                            executions,
                            p ->
                                    switch (p.getToolUseBlock().getId()) {
                                        case "error" ->
                                                Mono.just(ToolResultBlock.error("actual failure"));
                                        case "pending" ->
                                                Mono.error(
                                                        new ToolSuspendException(
                                                                "external result needed"));
                                        default -> Mono.just(ToolResultBlock.success("executed"));
                                    }));
            List<String> hooks = new ArrayList<>();
            ReActAgent.Builder builder =
                    ReActAgent.builder()
                            .name("assistant")
                            .model(model)
                            .toolkit(toolkit)
                            .hook(hook(event -> hooks.add(event.getToolUse().getId())));
            if (stop) {
                builder.middleware(stopAfterActing());
            }
            try (ReActAgent agent = builder.build()) {
                RuntimeContext ctx = RuntimeContext.builder().sessionId("suspension").build();
                Msg result = invokeRun(agent, ctx, streaming);
                assertEquals(
                        stop
                                ? GenerateReason.MIDDLEWARE_STOP_REQUESTED
                                : GenerateReason.TOOL_SUSPENDED,
                        result.getGenerateReason());
                assertEquals(3, executions.get());
                assertEquals(List.of("success", "error"), hooks);
                List<ToolResultBlock> history = allResults(agent.getAgentState(ctx).getContext());
                assertEquals(
                        List.of(ToolResultState.SUCCESS, ToolResultState.ERROR),
                        history.stream().map(ToolResultBlock::getState).toList());
                assertEquals(
                        "Error: actual failure",
                        ((TextBlock) history.get(1).getOutput().get(0)).getText());
                List<ToolUseBlock> pending =
                        io.agentscope.core.util.MessageUtils.extractPendingToolCalls(
                                agent.getAgentState(ctx).getContext(), "assistant");
                assertEquals(
                        List.of("pending"), pending.stream().map(ToolUseBlock::getId).toList());
                ToolResultBlock suspended =
                        result.getContentBlocks(ToolResultBlock.class).stream()
                                .filter(block -> "pending".equals(block.getId()))
                                .findFirst()
                                .orElseThrow();
                assertEquals(ToolResultState.SUSPENDED, suspended.getState());
                assertEquals(
                        "external result needed",
                        suspended.getMetadata().get(ToolResultBlock.METADATA_SUSPEND_REASON));
                assertEquals(1, model.getCallCount());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void permissionAskingKeepsTheAssistantRequestAndDoesNotExecute(boolean streaming) {
        MockModel model = batchModel(List.of(call("ask", "approval")));
        AtomicInteger executions = new AtomicInteger();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new ToolBase(
                        ToolBase.builder()
                                .name("approval")
                                .description("Fake approval tool")
                                .inputSchema(Map.of("type", "object"))) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> input, PermissionContextState context) {
                        return Mono.just(PermissionDecision.ask("confirm"));
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        executions.incrementAndGet();
                        return Mono.just(ToolResultBlock.success("never execute"));
                    }
                });
        List<String> hooks = new ArrayList<>();
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        RuntimeContext ctx = RuntimeContext.builder().sessionId("approval").build();
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("assistant")
                        .model(model)
                        .toolkit(toolkit)
                        .stateStore(store)
                        .hook(hook(event -> hooks.add(event.getToolUse().getId())))
                        .build()) {
            Msg result = invokeRun(agent, ctx, streaming);
            assertEquals(GenerateReason.PERMISSION_ASKING, result.getGenerateReason());
            assertEquals(
                    List.of("ask"),
                    result.getContentBlocks(ToolUseBlock.class).stream()
                            .map(ToolUseBlock::getId)
                            .toList());
            assertTrue(result.getContentBlocks(ToolResultBlock.class).isEmpty());
            assertTrue(allResults(agent.getAgentState(ctx).getContext()).isEmpty());
            assertEquals(
                    ToolCallState.ASKING,
                    result.getContentBlocks(ToolUseBlock.class).get(0).getState());
            assertTrue(hooks.isEmpty());
            assertEquals(0, executions.get());
            assertEquals(1, model.getCallCount());
        }
        try (ReActAgent restored =
                ReActAgent.builder()
                        .name("assistant")
                        .model(model)
                        .toolkit(toolkit)
                        .stateStore(store)
                        .build()) {
            List<ToolUseBlock> pending =
                    io.agentscope.core.util.MessageUtils.extractPendingToolCalls(
                            restored.getAgentState(ctx).getContext(), "assistant");
            assertEquals(1, pending.size());
            assertEquals(ToolCallState.ASKING, pending.get(0).getState());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eventObserversRunInRegistrationOrderAndCannotChangeToolFacts(boolean streaming) {
        List<String> observed = new ArrayList<>();
        AtomicInteger optedOut = new AtomicInteger();
        MiddlewareBase throwingObserver =
                new MiddlewareBase() {
                    @Override
                    public void onAgentEvent(Agent agent, RuntimeContext ctx, AgentEvent event) {
                        assertNotNull(
                                event.getExecution(), "production identity precedes notification");
                        assertNotNull(ctx.getAgentState());
                        if (event instanceof ToolResultEndEvent) {
                            observed.add("first");
                            throw new IllegalStateException("observer failure");
                        }
                    }
                };
        MiddlewareBase secondObserver =
                new MiddlewareBase() {
                    @Override
                    public void onAgentEvent(Agent agent, RuntimeContext ctx, AgentEvent event) {
                        if (event instanceof ToolResultEndEvent) {
                            observed.add("second");
                        }
                    }
                };
        MiddlewareBase disabledObserver =
                new MiddlewareBase() {
                    @Override
                    public Set<ExtensionPoint> activePoints() {
                        return Set.of();
                    }

                    @Override
                    public void onAgentEvent(Agent agent, RuntimeContext ctx, AgentEvent event) {
                        optedOut.incrementAndGet();
                    }
                };
        try (ReActAgent agent =
                builder(toolThenDoneModel())
                        .middlewares(List.of(throwingObserver, disabledObserver, secondObserver))
                        .build()) {
            Msg result = invoke(agent, streaming);
            assertEquals("done", result.getTextContent());
            assertFeedback(
                    toolResult(agent.getAgentState().getContext()),
                    "raw output",
                    ToolResultState.SUCCESS);
        }
        assertEquals(
                List.of("first", "second"),
                observed,
                "notifications are once per produced result, with isolated failures");
        assertEquals(0, optedOut.get());
    }

    private static ToolBase fixture(
            AtomicInteger executions, Function<ToolCallParam, Mono<ToolResultBlock>> result) {
        return new ToolBase(
                ToolBase.builder()
                        .name("fixture")
                        .description("Fake fixture")
                        .inputSchema(Map.of("type", "object"))
                        .readOnly(true)
                        .returnDirect(true)) {
            @Override
            public Mono<PermissionDecision> checkPermissions(
                    Map<String, Object> input, PermissionContextState context) {
                return Mono.just(PermissionDecision.allow("fixture"));
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                executions.incrementAndGet();
                return result.apply(param);
            }
        };
    }

    private static ToolUseBlock call(String id, String name) {
        return ToolUseBlock.builder().id(id).name(name).input(Map.of()).content("{}").build();
    }

    private static MockModel batchModel(List<ToolUseBlock> calls) {
        return new MockModel(
                messages ->
                        List.of(ChatResponse.builder().content(new ArrayList<>(calls)).build()));
    }

    private static MiddlewareBase stopAfterActing() {
        return new MiddlewareBase() {
            @Override
            public Set<ExtensionPoint> activePoints() {
                return Set.of(ExtensionPoint.ON_ACTING);
            }

            @Override
            public Flux<AgentEvent> onActing(
                    Agent agent,
                    RuntimeContext ctx,
                    ActingInput input,
                    Function<ActingInput, Flux<AgentEvent>> next) {
                return next.apply(input)
                        .concatWith(
                                Flux.just(
                                        new RequestStopEvent(
                                                "fixture stop",
                                                GenerateReason.MIDDLEWARE_STOP_REQUESTED)));
            }
        };
    }

    private static Msg invokeRun(ReActAgent agent, RuntimeContext ctx, boolean streaming) {
        Msg result;
        RunOutcome outcome;
        if (streaming) {
            AgentRun<AgentEvent> run = agent.prepareRun(List.of(), ctx);
            result =
                    run.stream()
                            .ofType(AgentResultEvent.class)
                            .map(AgentResultEvent::getResult)
                            .single()
                            .block(Duration.ofSeconds(10));
            outcome = run.outcome().block(Duration.ofSeconds(10));
        } else {
            AgentRun<Msg> run = agent.prepareCall(List.of(), ctx);
            result = run.stream().single().block(Duration.ofSeconds(10));
            outcome = run.outcome().block(Duration.ofSeconds(10));
        }
        assertNotNull(result);
        assertEquals(RunOutcome.fromResult(result), outcome);
        return result;
    }

    private static List<ToolResultBlock> allResults(List<Msg> messages) {
        return messages.stream()
                .flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                .toList();
    }

    private static Hook hook(Consumer<PostActingEvent> handler) {
        return new Hook() {
            @Override
            public <T extends HookEvent> Mono<T> onEvent(T event) {
                if (event instanceof PostActingEvent postActing) {
                    handler.accept(postActing);
                }
                return Mono.just(event);
            }
        };
    }

    private static ReActAgent.Builder builder(MockModel model) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new ToolBase(
                        ToolBase.builder()
                                .name("lookup")
                                .description("Look up a value")
                                .inputSchema(Map.of("type", "object"))
                                .readOnly(true)) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> input, PermissionContextState context) {
                        return Mono.just(PermissionDecision.allow("test"));
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.just(ToolResultBlock.success("raw output"));
                    }
                });
        return ReActAgent.builder()
                .name("assistant")
                .sysPrompt("base system prompt")
                .model(model)
                .toolkit(toolkit);
    }

    private static Msg invoke(ReActAgent agent, boolean streaming) {
        if (!streaming) {
            return agent.call(List.of()).block(Duration.ofSeconds(10));
        }
        return agent.streamEvents(List.of())
                .ofType(AgentResultEvent.class)
                .map(AgentResultEvent::getResult)
                .single()
                .block(Duration.ofSeconds(10));
    }

    private static ToolResultBlock toolResult(List<Msg> messages) {
        return messages.stream()
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .findFirst()
                .orElseThrow();
    }

    private static void assertFeedback(ToolResultBlock result, String text, ToolResultState state) {
        assertEquals("tc1", result.getId());
        assertEquals("lookup", result.getName());
        assertEquals(state, result.getState());
        assertEquals(1, result.getOutput().size());
        assertEquals(text, ((TextBlock) result.getOutput().get(0)).getText());
    }

    private static MockModel toolThenDoneModel() {
        return new MockModel(
                messages -> {
                    boolean hasToolResult =
                            messages.stream()
                                    .anyMatch(
                                            message ->
                                                    !message.getContentBlocks(ToolResultBlock.class)
                                                            .isEmpty());
                    if (hasToolResult) {
                        return List.of(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("done").build()))
                                        .build());
                    }
                    return List.of(
                            ChatResponse.builder()
                                    .content(
                                            List.of(
                                                    ToolUseBlock.builder()
                                                            .id("tc1")
                                                            .name("lookup")
                                                            .input(Map.of())
                                                            .content("{}")
                                                            .build()))
                                    .build());
                });
    }
}
