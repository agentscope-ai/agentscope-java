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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.bus.AsyncToolRecord;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.bus.WorkspaceAsyncToolRegistry;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class AsyncToolMiddlewareTest {

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir;
    private LocalFilesystem fs;
    private MessageBus bus;
    private AgentState agentState;
    private RuntimeContext ctx;

    @BeforeEach
    void setUp() {
        fs = new LocalFilesystem(tempDir, true, 10);
        bus = new WorkspaceMessageBus(fs, "/bus");
        agentState = AgentState.builder().build();
        agentState.setReplyId("reply-1");
        ctx = RuntimeContext.builder().sessionId("session-1").agentState(agentState).build();
    }

    @Test
    void toolCompletesBeforeTimeout_passesThrough() {
        AsyncToolMiddleware middleware = new AsyncToolMiddleware(bus, Duration.ofSeconds(5));

        ToolUseBlock tool = ToolUseBlock.builder().id("t1").name("fast_tool").build();
        ActingInput input = new ActingInput(List.of(tool));

        Flux<AgentEvent> downstream =
                Flux.just(
                        new ToolResultStartEvent("r1", "t1", "fast_tool"),
                        new ToolResultTextDeltaEvent("r1", "t1", "fast_tool", "result text"),
                        new ToolResultEndEvent("r1", "t1", "fast_tool", ToolResultState.SUCCESS));

        List<AgentEvent> events =
                middleware
                        .onActing(stubAgent(), ctx, input, next -> downstream)
                        .collectList()
                        .block();

        assertEquals(3, events.size());
        assertTrue(events.get(0) instanceof ToolResultStartEvent);
        assertTrue(events.get(1) instanceof ToolResultTextDeltaEvent);
        assertTrue(events.get(2) instanceof ToolResultEndEvent);

        ToolResultTextDeltaEvent delta = (ToolResultTextDeltaEvent) events.get(1);
        assertEquals("result text", delta.getDelta());

        List<BusEntry> inbox = bus.inboxDrain("session-1", 100).block();
        assertTrue(inbox.isEmpty());
    }

    @Test
    void toolExceedsTimeout_emitsPlaceholderAndOffloads() throws Exception {
        AsyncToolMiddleware middleware = new AsyncToolMiddleware(bus, Duration.ofMillis(200));

        ToolUseBlock tool = ToolUseBlock.builder().id("t1").name("slow_tool").build();
        ActingInput input = new ActingInput(List.of(tool));

        Flux<AgentEvent> slowDownstream =
                Flux.just((AgentEvent) new ToolResultStartEvent("r1", "t1", "slow_tool"))
                        .concatWith(
                                Mono.delay(Duration.ofSeconds(2))
                                        .thenMany(
                                                Flux.just(
                                                        new ToolResultTextDeltaEvent(
                                                                "r1",
                                                                "t1",
                                                                "slow_tool",
                                                                "slow result"),
                                                        new ToolResultEndEvent(
                                                                "r1",
                                                                "t1",
                                                                "slow_tool",
                                                                ToolResultState.SUCCESS))));

        List<AgentEvent> events =
                middleware
                        .onActing(stubAgent(), ctx, input, next -> slowDownstream)
                        .collectList()
                        .block();

        boolean hasPlaceholder =
                events.stream()
                        .anyMatch(
                                e ->
                                        e instanceof ToolResultTextDeltaEvent d
                                                && d.getDelta().contains("running in background"));
        assertTrue(hasPlaceholder, "Should emit placeholder text delta");

        boolean hasEnd =
                events.stream()
                        .anyMatch(
                                e ->
                                        e instanceof ToolResultEndEvent end
                                                && end.getState() == ToolResultState.SUCCESS);
        assertTrue(hasEnd, "Should emit end event");

        assertFalse(agentState.getContext().isEmpty(), "Placeholder should be written to context");

        Thread.sleep(3000);

        List<BusEntry> inbox = bus.inboxDrain("session-1", 100).block();
        assertFalse(inbox.isEmpty(), "Background result should be pushed to inbox");

        String hint = inbox.get(0).payload().get("hint").toString();
        assertTrue(hint.contains("slow result"), "Inbox should contain the actual result");
    }

    @Test
    void fastToolCompletesBeforeTimeout_slowToolOffloadsWithoutDuplicatingOrLosingFastResult()
            throws Exception {
        RecordingAsyncToolRegistry registry =
                new RecordingAsyncToolRegistry(new WorkspaceAsyncToolRegistry(fs, "/async-tools"));
        AsyncToolMiddleware middleware =
                new AsyncToolMiddleware(bus, Duration.ofMillis(200), registry);

        ToolUseBlock fast = ToolUseBlock.builder().id("t1").name("fast_tool").build();
        ToolUseBlock slow = ToolUseBlock.builder().id("t2").name("slow_tool").build();
        ActingInput input = new ActingInput(List.of(fast, slow));

        Sinks.One<String> releaseSlow = Sinks.one();
        Flux<AgentEvent> downstream =
                Flux.<AgentEvent>just(
                                new ToolResultStartEvent("r1", "t1", "fast_tool"),
                                new ToolResultTextDeltaEvent(
                                        "r1", "t1", "fast_tool", "fast output"),
                                new ToolResultEndEvent(
                                        "r1", "t1", "fast_tool", ToolResultState.SUCCESS),
                                new ToolResultStartEvent("r1", "t2", "slow_tool"))
                        .concatWith(
                                releaseSlow
                                        .asMono()
                                        .flatMapMany(
                                                val ->
                                                        Flux.just(
                                                                new ToolResultTextDeltaEvent(
                                                                        "r1",
                                                                        "t2",
                                                                        "slow_tool",
                                                                        "slow output"),
                                                                new ToolResultEndEvent(
                                                                        "r1",
                                                                        "t2",
                                                                        "slow_tool",
                                                                        ToolResultState.SUCCESS))));

        List<AgentEvent> events =
                middleware
                        .onActing(stubAgent(), ctx, input, next -> downstream)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        List<ToolResultStartEvent> fastStarts =
                events.stream()
                        .filter(ToolResultStartEvent.class::isInstance)
                        .map(ToolResultStartEvent.class::cast)
                        .filter(e -> "t1".equals(e.getToolCallId()))
                        .toList();
        List<ToolResultTextDeltaEvent> fastDeltas =
                events.stream()
                        .filter(ToolResultTextDeltaEvent.class::isInstance)
                        .map(ToolResultTextDeltaEvent.class::cast)
                        .filter(e -> "t1".equals(e.getToolCallId()))
                        .toList();
        List<ToolResultEndEvent> fastEnds =
                events.stream()
                        .filter(ToolResultEndEvent.class::isInstance)
                        .map(ToolResultEndEvent.class::cast)
                        .filter(e -> "t1".equals(e.getToolCallId()))
                        .toList();
        assertEquals(1, fastStarts.size());
        assertEquals(1, fastDeltas.size());
        assertEquals("fast output", fastDeltas.get(0).getDelta());
        assertEquals(1, fastEnds.size());
        assertEquals(ToolResultState.SUCCESS, fastEnds.get(0).getState());

        assertTrue(
                events.stream()
                        .anyMatch(
                                e ->
                                        e instanceof ToolResultTextDeltaEvent d
                                                && "t2".equals(d.getToolCallId())
                                                && d.getDelta().contains("running in background")));

        List<ToolResultBlock> contextResults =
                agentState.getContext().stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .toList();
        assertEquals(
                List.of("t1", "t2"), contextResults.stream().map(ToolResultBlock::getId).toList());
        assertEquals(
                "fast output", ((TextBlock) contextResults.get(0).getOutput().get(0)).getText());
        assertEquals(ToolResultState.SUCCESS, contextResults.get(0).getState());
        assertTrue(
                ((TextBlock) contextResults.get(1).getOutput().get(0))
                        .getText()
                        .contains("running in background"));

        assertEquals(List.of("t2"), registry.registeredIds);

        releaseSlow.tryEmitValue("go");
        assertTrue(registry.completeLatch.await(5, TimeUnit.SECONDS));
        assertEquals(Map.of("t2", "slow output"), registry.completedById);

        List<BusEntry> inbox = awaitInboxEntries("session-1");
        assertEquals(1, inbox.size());
        String hint = inbox.get(0).payload().get("hint").toString();
        assertTrue(hint.contains("slow_tool"));
        assertTrue(hint.contains("slow output"));
        assertFalse(hint.contains("fast_tool"));
        assertFalse(hint.contains("fast output"));
    }

    @Test
    void endToEndReActAgentWithAsyncToolMiddleware_preservesFastToolResultOnSlowOffload()
            throws Exception {
        RecordingAsyncToolRegistry registry =
                new RecordingAsyncToolRegistry(new WorkspaceAsyncToolRegistry(fs, "/async-tools"));
        AsyncToolMiddleware middleware =
                new AsyncToolMiddleware(bus, Duration.ofMillis(300), registry);

        Sinks.One<ToolResultBlock> slowSink = Sinks.one();
        CountDownLatch slowSubscribed = new CountDownLatch(1);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(
                namedTool("fast_tool", p -> Mono.just(ToolResultBlock.text("fast value"))));
        toolkit.registerTool(
                namedTool(
                        "slow_tool",
                        p -> slowSink.asMono().doOnSubscribe(s -> slowSubscribed.countDown())));

        AtomicInteger round = new AtomicInteger();
        List<List<Msg>> modelInputs = new CopyOnWriteArrayList<>();
        Model model =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        modelInputs.add(List.copyOf(messages));
                        if (round.getAndIncrement() == 0) {
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            ToolUseBlock.builder()
                                                                    .id("t1")
                                                                    .name("fast_tool")
                                                                    .input(Map.of())
                                                                    .content("{}")
                                                                    .build(),
                                                            ToolUseBlock.builder()
                                                                    .id("t2")
                                                                    .name("slow_tool")
                                                                    .input(Map.of())
                                                                    .content("{}")
                                                                    .build()))
                                            .build());
                        }
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("continued")
                                                                .build()))
                                        .build());
                    }

                    @Override
                    public String getModelName() {
                        return "mock-async-model";
                    }
                };

        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("async-test")
                        .model(model)
                        .toolkit(toolkit)
                        .middleware(middleware)
                        .build()) {
            RuntimeContext rc = RuntimeContext.builder().sessionId("session-1").build();
            List<AgentEvent> events =
                    agent.streamEvents(List.of(), rc).collectList().block(Duration.ofSeconds(5));

            assertTrue(slowSubscribed.await(3, TimeUnit.SECONDS));
            assertEquals(2, modelInputs.size());

            List<ToolResultBlock> secondRoundResults =
                    modelInputs.get(1).stream()
                            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .toList();
            assertEquals(
                    List.of("t1", "t2"),
                    secondRoundResults.stream().map(ToolResultBlock::getId).toList());
            assertEquals(
                    "fast value",
                    ((TextBlock) secondRoundResults.get(0).getOutput().get(0)).getText());
            assertTrue(
                    ((TextBlock) secondRoundResults.get(1).getOutput().get(0))
                            .getText()
                            .contains("running in background"));

            assertEquals(
                    1,
                    events.stream()
                            .filter(ToolResultEndEvent.class::isInstance)
                            .map(ToolResultEndEvent.class::cast)
                            .filter(e -> "t1".equals(e.getToolCallId()))
                            .count());
            assertEquals(List.of("t2"), registry.registeredIds);

            slowSink.tryEmitValue(ToolResultBlock.text("slow value"));
            assertTrue(registry.completeLatch.await(5, TimeUnit.SECONDS));
            assertEquals(Map.of("t2", "slow value"), registry.completedById);

            List<BusEntry> inbox = awaitInboxEntries("session-1");
            assertEquals(1, inbox.size());
            String hint = inbox.get(0).payload().get("hint").toString();
            assertTrue(hint.contains("slow_tool"));
            assertTrue(hint.contains("slow value"));
            assertFalse(hint.contains("fast_tool"));
            assertFalse(hint.contains("fast value"));
        }
    }

    @Test
    void backgroundErrorAfterOffload_onlyFailsOffloadedToolsInRegistry() throws Exception {
        RecordingAsyncToolRegistry registry =
                new RecordingAsyncToolRegistry(new WorkspaceAsyncToolRegistry(fs, "/async-tools"));
        AsyncToolMiddleware middleware =
                new AsyncToolMiddleware(bus, Duration.ofMillis(200), registry);

        ToolUseBlock fast = ToolUseBlock.builder().id("t1").name("fast_tool").build();
        ToolUseBlock slow = ToolUseBlock.builder().id("t2").name("slow_tool").build();
        ActingInput input = new ActingInput(List.of(fast, slow));

        Sinks.One<String> releaseSlowError = Sinks.one();
        Flux<AgentEvent> downstream =
                Flux.<AgentEvent>just(
                                new ToolResultStartEvent("r1", "t1", "fast_tool"),
                                new ToolResultTextDeltaEvent(
                                        "r1", "t1", "fast_tool", "fast output"),
                                new ToolResultEndEvent(
                                        "r1", "t1", "fast_tool", ToolResultState.SUCCESS),
                                new ToolResultStartEvent("r1", "t2", "slow_tool"))
                        .concatWith(
                                releaseSlowError
                                        .asMono()
                                        .thenMany(Flux.error(new RuntimeException("slow boom"))));

        middleware
                .onActing(stubAgent(), ctx, input, next -> downstream)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertEquals(List.of("t2"), registry.registeredIds);
        releaseSlowError.tryEmitValue("fail");
        assertTrue(registry.failLatch.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("t2"), registry.failedIds);
    }

    private List<BusEntry> awaitInboxEntries(String sessionId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            List<BusEntry> entries = bus.inboxDrain(sessionId, 100).block();
            if (entries != null && !entries.isEmpty()) {
                return entries;
            }
            Thread.sleep(20);
        }
        return bus.inboxDrain(sessionId, 100).block();
    }

    private static AgentTool namedTool(
            String name, Function<ToolCallParam, Mono<ToolResultBlock>> body) {
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
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return body.apply(param);
            }
        };
    }

    private static final class RecordingAsyncToolRegistry implements AsyncToolRegistry {
        private final AsyncToolRegistry delegate;
        private final List<String> registeredIds = new CopyOnWriteArrayList<>();
        private final Map<String, String> completedById = new ConcurrentHashMap<>();
        private final List<String> failedIds = new CopyOnWriteArrayList<>();
        private final CountDownLatch completeLatch = new CountDownLatch(1);
        private final CountDownLatch failLatch = new CountDownLatch(1);

        private RecordingAsyncToolRegistry(AsyncToolRegistry delegate) {
            this.delegate = delegate;
        }

        @Override
        public Mono<Void> register(AsyncToolRecord record) {
            registeredIds.add(record.id());
            return delegate.register(record);
        }

        @Override
        public Mono<Void> complete(String id, String result) {
            completedById.put(id, result);
            return delegate.complete(id, result).doOnTerminate(completeLatch::countDown);
        }

        @Override
        public Mono<Void> fail(String id, String error) {
            failedIds.add(id);
            return delegate.fail(id, error).doOnTerminate(failLatch::countDown);
        }

        @Override
        public Mono<List<AsyncToolRecord>> findStale(String sessionId, Duration ttl) {
            return delegate.findStale(sessionId, ttl);
        }

        @Override
        public Mono<Void> markTimeout(String id) {
            return delegate.markTimeout(id);
        }
    }

    private static io.agentscope.core.agent.Agent stubAgent() {
        return new io.agentscope.core.agent.Agent() {
            @Override
            public String getAgentId() {
                return "test-agent-id";
            }

            @Override
            public String getName() {
                return "TestAgent";
            }

            @Override
            public void interrupt() {}

            @Override
            public void interrupt(Msg msg) {}

            @Override
            public io.agentscope.core.state.AgentState getAgentState() {
                return null;
            }

            @Override
            public Mono<Msg> call(List<Msg> msgs) {
                return Mono.empty();
            }

            @Override
            public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel) {
                return Mono.empty();
            }

            @Override
            public Mono<Msg> call(List<Msg> msgs, com.fasterxml.jackson.databind.JsonNode schema) {
                return Mono.empty();
            }

            @Override
            public Flux<io.agentscope.core.agent.Event> stream(
                    List<Msg> msgs, io.agentscope.core.agent.StreamOptions options) {
                return Flux.empty();
            }

            @Override
            public Flux<io.agentscope.core.agent.Event> stream(
                    List<Msg> msgs, io.agentscope.core.agent.StreamOptions options, Class<?> m) {
                return Flux.empty();
            }

            @Override
            public Flux<io.agentscope.core.agent.Event> stream(
                    List<Msg> msgs,
                    io.agentscope.core.agent.StreamOptions options,
                    com.fasterxml.jackson.databind.JsonNode schema) {
                return Flux.empty();
            }

            @Override
            public Mono<Void> observe(Msg msg) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> observe(List<Msg> msgs) {
                return Mono.empty();
            }
        };
    }
}
