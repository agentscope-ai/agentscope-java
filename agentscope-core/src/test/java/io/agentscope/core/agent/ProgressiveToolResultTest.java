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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolExecutionCallback;
import io.agentscope.core.tool.ToolRequestConfig;
import io.agentscope.core.tool.ToolSuspendException;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class ProgressiveToolResultTest {
    public static class Response {
        public String answer;
    }

    @Test
    void mixedStructuredBatchEmitsEachResultOnce() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool("fast", p -> Mono.just(ToolResultBlock.text("regular"))));
        Map<String, Object> input = Map.of("response", Map.of("answer", "done"));
        MockModel model =
                new MockModel(
                        messages ->
                                List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("structured")
                                                                        .name("generate_response")
                                                                        .input(input)
                                                                        .content(
                                                                                "{\"response\":{\"answer\":\"done\"}}")
                                                                        .build(),
                                                                call("fast", "regular")))
                                                .build()));
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (ReActAgent agent =
                ReActAgent.builder().name("test").model(model).toolkit(toolkit).build()) {
            var result =
                    agent.call(List.of(), Response.class)
                            .contextWrite(
                                    ctx ->
                                            ctx.put(
                                                    AgentEventEmitter.FORWARDING_CONTEXT_KEY,
                                                    (AgentEventEmitter) events::add))
                            .block(Duration.ofSeconds(5));
            assertEquals("done", result.getStructuredData(Response.class).answer);
            assertEquals(
                    List.of("regular", "structured"),
                    events.stream()
                            .filter(ToolResultEndEvent.class::isInstance)
                            .map(ToolResultEndEvent.class::cast)
                            .map(ToolResultEndEvent::getToolCallId)
                            .sorted()
                            .toList());
            assertEquals(2, events.stream().filter(ToolResultStartEvent.class::isInstance).count());
            assertEquals(1, model.getCallCount());
        }
    }

    @Test
    void completedToolIsVisibleBeforeSlowEarlierTool() throws Exception {
        Sinks.One<ToolResultBlock> slow = Sinks.one();
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch fastEnd = new CountDownLatch(1);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(
                tool("slow", p -> slow.asMono().doOnSubscribe(s -> subscribed.countDown())));
        toolkit.registerTool(tool("fast", p -> Mono.just(ToolResultBlock.text("fast result"))));
        MockModel model = model();
        List<String> postActing = new CopyOnWriteArrayList<>();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("test")
                        .model(model)
                        .toolkit(toolkit)
                        .hook(
                                new Hook() {
                                    @Override
                                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                                        if (event instanceof PostActingEvent post) {
                                            postActing.add(post.getToolUse().getId());
                                        }
                                        return Mono.just(event);
                                    }
                                })
                        .build();
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Disposable subscription =
                agent.streamEvents(List.of())
                        .subscribe(
                                e -> {
                                    events.add(e);
                                    if (e instanceof ToolResultEndEvent end
                                            && end.getToolCallId().equals("fast-call")) {
                                        fastEnd.countDown();
                                    }
                                },
                                e -> done.countDown(),
                                done::countDown);
        try {
            assertTrue(subscribed.await(3, TimeUnit.SECONDS));
            assertTrue(
                    fastEnd.await(3, TimeUnit.SECONDS),
                    "fast result must arrive while slow is pending");
            assertEquals(1, model.getCallCount(), "reasoning must wait for the whole batch");
            assertTrue(postActing.isEmpty(), "PostActing must wait for the whole batch");
            assertTrue(
                    events.stream()
                            .anyMatch(
                                    e ->
                                            e instanceof ToolResultTextDeltaEvent t
                                                    && t.getToolCallId().equals("fast-call")));
            slow.tryEmitValue(ToolResultBlock.text("slow result"));
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertEquals(2, model.getCallCount());
            assertEquals(List.of("slow-call", "fast-call"), postActing);
            assertEquals(
                    List.of("slow-call", "fast-call"),
                    agent.getAgentState().getContext().stream()
                            .flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                            .map(ToolResultBlock::getId)
                            .toList());
            assertEquals(
                    List.of("fast-call", "slow-call"),
                    events.stream()
                            .filter(ToolResultEndEvent.class::isInstance)
                            .map(ToolResultEndEvent.class::cast)
                            .map(ToolResultEndEvent::getToolCallId)
                            .toList());
        } finally {
            subscription.dispose();
            agent.close();
        }
    }

    @Test
    void serialToolsEmitStartWhenExecutionBegins() throws Exception {
        Sinks.One<ToolResultBlock> slow = Sinks.one();
        CountDownLatch subscribed = new CountDownLatch(1);
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder().parallel(false).build());
        toolkit.registerTool(
                tool("slow", p -> slow.asMono().doOnSubscribe(s -> subscribed.countDown())));
        toolkit.registerTool(tool("fast", p -> Mono.just(ToolResultBlock.text("fast"))));
        ReActAgent agent =
                ReActAgent.builder().name("test").model(model()).toolkit(toolkit).build();
        List<String> starts = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Disposable subscription =
                agent.streamEvents(List.of())
                        .subscribe(
                                e -> {
                                    if (e instanceof ToolResultStartEvent start) {
                                        starts.add(start.getToolCallId());
                                    }
                                },
                                e -> done.countDown(),
                                done::countDown);
        try {
            assertTrue(subscribed.await(3, TimeUnit.SECONDS));
            assertEquals(
                    List.of("slow-call"),
                    starts,
                    "while slow is still running, fast must not have a Start event yet");
            slow.tryEmitValue(ToolResultBlock.text("slow result"));
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertEquals(
                    List.of("slow-call", "fast-call"),
                    starts,
                    "fast starts only when its turn arrives, so Start->End spans actual execution");
        } finally {
            subscription.dispose();
            agent.close();
        }
    }

    @Test
    void streamingToolPreservesLegacyChunkDeltaAndEmitsEarlyEnd() throws Exception {
        Sinks.One<ToolResultBlock> slow = Sinks.one();
        CountDownLatch fastEnd = new CountDownLatch(1);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool("slow", p -> slow.asMono()));
        toolkit.registerTool(
                tool(
                        "fast",
                        p -> {
                            p.getEmitter().emit(ToolResultBlock.text("chunk-1"));
                            return Mono.just(ToolResultBlock.text("final-ignored"));
                        }));
        ReActAgent agent =
                ReActAgent.builder().name("test").model(model()).toolkit(toolkit).build();
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Disposable subscription =
                agent.streamEvents(List.of())
                        .subscribe(
                                e -> {
                                    events.add(e);
                                    if (e instanceof ToolResultEndEvent end
                                            && "fast-call".equals(end.getToolCallId())) {
                                        fastEnd.countDown();
                                    }
                                },
                                e -> done.countDown(),
                                done::countDown);
        try {
            assertTrue(fastEnd.await(3, TimeUnit.SECONDS));
            List<String> fastDeltas =
                    events.stream()
                            .filter(ToolResultTextDeltaEvent.class::isInstance)
                            .map(ToolResultTextDeltaEvent.class::cast)
                            .filter(e -> "fast-call".equals(e.getToolCallId()))
                            .map(ToolResultTextDeltaEvent::getDelta)
                            .toList();
            assertEquals(List.of("chunk-1"), fastDeltas);
            assertEquals(List.of(ToolResultState.SUCCESS), endStates(events, "fast-call"));
            slow.tryEmitValue(ToolResultBlock.text("slow result"));
            assertTrue(done.await(3, TimeUnit.SECONDS));
        } finally {
            subscription.dispose();
            agent.close();
        }
    }

    @Test
    void suspendedToolEmitsRunningEndAndRequireExternalExecution() throws Exception {
        Sinks.One<ToolResultBlock> slow = Sinks.one();
        CountDownLatch askEnd = new CountDownLatch(1);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(
                tool("ask", p -> Mono.error(new ToolSuspendException("external execution"))));
        toolkit.registerTool(tool("slow", p -> slow.asMono()));
        MockModel model =
                new MockModel(
                        messages ->
                                List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                call("ask", "ask-call"),
                                                                call("slow", "slow-call")))
                                                .build()));
        ReActAgent agent = ReActAgent.builder().name("test").model(model).toolkit(toolkit).build();
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Disposable subscription =
                agent.streamEvents(List.of())
                        .subscribe(
                                e -> {
                                    events.add(e);
                                    if (e instanceof ToolResultEndEvent end
                                            && "ask-call".equals(end.getToolCallId())) {
                                        askEnd.countDown();
                                    }
                                },
                                e -> done.countDown(),
                                done::countDown);
        try {
            assertTrue(askEnd.await(3, TimeUnit.SECONDS));
            assertEquals(List.of(ToolResultState.RUNNING), endStates(events, "ask-call"));
            assertTrue(
                    events.stream().noneMatch(RequireExternalExecutionEvent.class::isInstance),
                    "RequireExternalExecutionEvent must wait for batch completion");
            slow.tryEmitValue(ToolResultBlock.text("slow done"));
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertEquals(List.of(ToolResultState.SUCCESS), endStates(events, "slow-call"));
            RequireExternalExecutionEvent requireExternal =
                    events.stream()
                            .filter(RequireExternalExecutionEvent.class::isInstance)
                            .map(RequireExternalExecutionEvent.class::cast)
                            .findFirst()
                            .orElse(null);
            assertNotNull(requireExternal);
            assertEquals(1, requireExternal.getToolCalls().size());
            assertEquals("ask-call", requireExternal.getToolCalls().get(0).getId());
        } finally {
            subscription.dispose();
            agent.close();
        }
    }

    @Test
    void batchFallbackPreservesResolvedResultAndEmitsEachToolOnce() throws Exception {
        Toolkit toolkit =
                failingBatchToolkit(
                        true,
                        (toolCalls, executionCallback) -> {
                            ToolUseBlock slowCall = toolCalls.get(0);
                            ToolUseBlock fastCall = toolCalls.get(1);
                            executionCallback.onStart(slowCall);
                            executionCallback.onStart(fastCall);
                            executionCallback.onResult(
                                    fastCall,
                                    ToolResultBlock.text("keep me")
                                            .withIdAndName(fastCall.getId(), fastCall.getName()));
                            return Mono.error(
                                    new IllegalStateException("batch infrastructure failure"));
                        });
        ReActAgent agent =
                ReActAgent.builder().name("test").model(model()).toolkit(toolkit).build();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Disposable subscription =
                agent.streamEvents(List.of())
                        .subscribe(
                                events::add,
                                e -> {
                                    failure.set(e);
                                    done.countDown();
                                },
                                done::countDown);
        try {
            assertTrue(done.await(3, TimeUnit.SECONDS), "batch must settle through the fallback");
            assertNull(failure.get(), "the fallback converts the failure into tool results");

            List<String> fastText =
                    events.stream()
                            .filter(ToolResultTextDeltaEvent.class::isInstance)
                            .map(ToolResultTextDeltaEvent.class::cast)
                            .filter(e -> "fast-call".equals(e.getToolCallId()))
                            .map(ToolResultTextDeltaEvent::getDelta)
                            .toList();
            assertEquals(
                    List.of("keep me"), fastText, "fast keeps its real output, no second round");
            assertEquals(
                    List.of(ToolResultState.SUCCESS),
                    endStates(events, "fast-call"),
                    "exactly one SUCCESS end for the finished tool");
            assertEquals(
                    List.of(ToolResultState.ERROR),
                    endStates(events, "slow-call"),
                    "exactly one ERROR end for the failing tool");

            Map<String, String> resultsById = new HashMap<>();
            agent.getAgentState()
                    .getContext()
                    .forEach(
                            m ->
                                    m.getContentBlocks(ToolResultBlock.class)
                                            .forEach(
                                                    b ->
                                                            resultsById.putIfAbsent(
                                                                    b.getId(),
                                                                    b.getOutput().toString())));
            assertTrue(
                    resultsById.get("fast-call").contains("keep me"),
                    "the model keeps the real result of the finished tool");
            assertFalse(
                    resultsById.get("fast-call").contains("Tool execution failed"),
                    "the model must not be told the finished tool failed");
            assertTrue(
                    resultsById.get("slow-call").contains("Tool execution failed"),
                    "the failing tool reports the error");
        } finally {
            subscription.dispose();
            agent.close();
        }
    }

    @Test
    void batchFallbackKeepsEarlierSuspensionAlive() throws Exception {
        Toolkit toolkit =
                failingBatchToolkit(
                        true,
                        (toolCalls, executionCallback) -> {
                            ToolUseBlock askCall = toolCalls.get(0);
                            executionCallback.onStart(askCall);
                            executionCallback.onResult(askCall, ToolResultBlock.suspended(askCall));
                            return Mono.error(
                                    new IllegalStateException("batch infrastructure failure"));
                        });
        MockModel model =
                new MockModel(
                        messages ->
                                List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                call("ask", "ask-call"),
                                                                call("boom", "boom-call")))
                                                .build()));
        ReActAgent agent = ReActAgent.builder().name("test").model(model).toolkit(toolkit).build();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Disposable subscription =
                agent.streamEvents(List.of())
                        .subscribe(
                                events::add,
                                e -> {
                                    failure.set(e);
                                    done.countDown();
                                },
                                done::countDown);
        try {
            assertTrue(done.await(3, TimeUnit.SECONDS), "batch must settle through the fallback");
            assertNull(failure.get());
            assertTrue(
                    events.stream().anyMatch(RequireExternalExecutionEvent.class::isInstance),
                    "the earlier suspension must survive the batch error");
            assertEquals(
                    List.of(ToolResultState.ERROR),
                    endStates(events, "boom-call"),
                    "the failing tool reports the error exactly once");
            assertEquals(
                    1,
                    events.stream()
                            .filter(ToolResultStartEvent.class::isInstance)
                            .map(ToolResultStartEvent.class::cast)
                            .filter(e -> "boom-call".equals(e.getToolCallId()))
                            .count());
            assertEquals(
                    List.of(ToolResultState.RUNNING),
                    endStates(events, "ask-call"),
                    "2.0.x preserves RUNNING end for suspended call without demoting to ERROR");
        } finally {
            subscription.dispose();
            agent.close();
        }
    }

    @Test
    void serialBatchFallbackEmitsStartAndErrorEndForQueuedTool() throws Exception {
        Toolkit toolkit =
                failingBatchToolkit(
                        false,
                        (toolCalls, executionCallback) -> {
                            executionCallback.onStart(toolCalls.get(0));
                            return Mono.error(
                                    new IllegalStateException("first call infra failure"));
                        });
        MockModel model =
                new MockModel(
                        messages ->
                                List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                call("first", "first-call"),
                                                                call("second", "second-call")))
                                                .build()));
        ReActAgent agent = ReActAgent.builder().name("test").model(model).toolkit(toolkit).build();
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Disposable subscription =
                agent.streamEvents(List.of())
                        .subscribe(events::add, e -> done.countDown(), done::countDown);
        try {
            assertTrue(done.await(3, TimeUnit.SECONDS));
            List<String> secondTypes =
                    events.stream()
                            .filter(
                                    e ->
                                            (e instanceof ToolResultStartEvent s
                                                            && "second-call"
                                                                    .equals(s.getToolCallId()))
                                                    || (e instanceof ToolResultTextDeltaEvent d
                                                            && "second-call"
                                                                    .equals(d.getToolCallId()))
                                                    || (e instanceof ToolResultEndEvent end
                                                            && "second-call"
                                                                    .equals(end.getToolCallId())))
                            .map(e -> e.getClass().getSimpleName())
                            .toList();
            assertEquals(
                    List.of(
                            "ToolResultStartEvent",
                            "ToolResultTextDeltaEvent",
                            "ToolResultEndEvent"),
                    secondTypes);
            assertTrue(
                    events.stream()
                            .filter(ToolResultTextDeltaEvent.class::isInstance)
                            .map(ToolResultTextDeltaEvent.class::cast)
                            .filter(e -> "second-call".equals(e.getToolCallId()))
                            .anyMatch(e -> e.getDelta().contains("first call infra failure")));
            assertEquals(List.of(ToolResultState.ERROR), endStates(events, "first-call"));
            assertEquals(List.of(ToolResultState.ERROR), endStates(events, "second-call"));
            assertEquals(
                    List.of("first-call", "second-call"),
                    agent.getAgentState().getContext().stream()
                            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .map(ToolResultBlock::getId)
                            .toList());
        } finally {
            subscription.dispose();
            agent.close();
        }
    }

    @Test
    void unrecoveredBatchErrorDoesNotRepeatResultOrCommitBatch() throws Exception {
        Toolkit toolkit =
                failingBatchToolkit(
                        true,
                        (toolCalls, executionCallback) -> {
                            ToolUseBlock fastCall = toolCalls.get(1);
                            executionCallback.onStart(fastCall);
                            executionCallback.onResult(
                                    fastCall,
                                    ToolResultBlock.text("keep me")
                                            .withIdAndName(fastCall.getId(), fastCall.getName()));
                            return Mono.error(new Error("infra failure"));
                        });
        ReActAgent agent =
                ReActAgent.builder().name("test").model(model()).toolkit(toolkit).build();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Disposable subscription =
                agent.streamEvents(List.of())
                        .subscribe(
                                events::add,
                                e -> {
                                    failure.set(e);
                                    done.countDown();
                                },
                                done::countDown);
        try {
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertNotNull(failure.get());
            assertEquals("infra failure", failure.get().getMessage());
            assertEquals(
                    1,
                    events.stream()
                            .filter(
                                    e ->
                                            e instanceof ToolResultEndEvent end
                                                    && "fast-call".equals(end.getToolCallId()))
                            .count());
            assertTrue(
                    agent.getAgentState().getContext().stream()
                            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .noneMatch(result -> "fast-call".equals(result.getId())),
                    "early result events must not write messages before batch settlement");
        } finally {
            subscription.dispose();
            agent.close();
        }
    }

    @Test
    void interruptedBatchErrorDoesNotRepeatResult() throws Exception {
        Toolkit toolkit =
                failingBatchToolkit(
                        true,
                        (toolCalls, executionCallback) -> {
                            ToolUseBlock fastCall = toolCalls.get(1);
                            executionCallback.onStart(fastCall);
                            executionCallback.onResult(
                                    fastCall,
                                    ToolResultBlock.text("keep me")
                                            .withIdAndName(fastCall.getId(), fastCall.getName()));
                            return Mono.error(new InterruptedException("stopped"));
                        });
        ReActAgent agent =
                ReActAgent.builder().name("test").model(model()).toolkit(toolkit).build();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Disposable subscription =
                agent.streamEvents(List.of())
                        .subscribe(
                                events::add,
                                e -> {
                                    failure.set(e);
                                    done.countDown();
                                },
                                done::countDown);
        try {
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(List.of(ToolResultState.SUCCESS), endStates(events, "fast-call"));
            assertTrue(
                    events.stream()
                            .filter(AgentResultEvent.class::isInstance)
                            .map(AgentResultEvent.class::cast)
                            .anyMatch(
                                    r ->
                                            r.getResult().getGenerateReason()
                                                    == GenerateReason.INTERRUPTED));
        } finally {
            subscription.dispose();
            agent.close();
        }
    }

    @FunctionalInterface
    private interface BatchCallHandler {
        Mono<List<ToolResultBlock>> handle(
                List<ToolUseBlock> toolCalls, ToolExecutionCallback callback);
    }

    private static Toolkit failingBatchToolkit(boolean parallel, BatchCallHandler handler) {
        return new Toolkit(ToolkitConfig.builder().parallel(parallel).build()) {
            @Override
            public Toolkit copy() {
                return this;
            }

            @Override
            public Mono<List<ToolResultBlock>> callToolsWithCallback(
                    List<ToolUseBlock> toolCalls,
                    ExecutionConfig agentExecutionConfig,
                    Agent agent,
                    RuntimeContext agentRuntimeContext,
                    ToolRequestConfig requestConfig,
                    ToolExecutionCallback executionCallback) {
                return Mono.defer(() -> handler.handle(toolCalls, executionCallback));
            }
        };
    }

    private static List<ToolResultState> endStates(List<AgentEvent> events, String callId) {
        return events.stream()
                .filter(ToolResultEndEvent.class::isInstance)
                .map(ToolResultEndEvent.class::cast)
                .filter(e -> callId.equals(e.getToolCallId()))
                .map(ToolResultEndEvent::getState)
                .toList();
    }

    static MockModel model() {
        AtomicInteger round = new AtomicInteger();
        return new MockModel(
                messages ->
                        List.of(
                                ChatResponse.builder()
                                        .content(
                                                round.getAndIncrement() == 0
                                                        ? List.of(
                                                                call("slow", "slow-call"),
                                                                call("fast", "fast-call"))
                                                        : List.of(
                                                                TextBlock.builder()
                                                                        .text("done")
                                                                        .build()))
                                        .build()));
    }

    static ToolUseBlock call(String name, String id) {
        return ToolUseBlock.builder().name(name).id(id).input(Map.of()).content("{}").build();
    }

    static AgentTool tool(String name, Function<ToolCallParam, Mono<ToolResultBlock>> body) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name;
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam p) {
                return body.apply(p);
            }
        };
    }
}
