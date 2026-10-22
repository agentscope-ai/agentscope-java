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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.observation.ActionObservationException;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import java.time.Duration;
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
                                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                                        if (event instanceof PostActingEvent post)
                                            postActing.add(post.getToolUse().getId());
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
                                            && end.getToolCallId().equals("fast-call"))
                                        fastEnd.countDown();
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
    void serialToolsKeepOriginalBatchStartEvents() throws Exception {
        Sinks.One<ToolResultBlock> slow = Sinks.one();
        CountDownLatch subscribed = new CountDownLatch(1);
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder().parallel(false).build());
        toolkit.registerTool(
                tool("slow", p -> slow.asMono().doOnSubscribe(s -> subscribed.countDown())));
        toolkit.registerTool(tool("fast", p -> Mono.just(ToolResultBlock.text("fast"))));
        ReActAgent agent =
                ReActAgent.builder().name("test").model(model()).toolkit(toolkit).build();
        List<String> starts = new CopyOnWriteArrayList<>();
        Disposable subscription =
                agent.streamEvents(List.of())
                        .subscribe(
                                e -> {
                                    if (e instanceof ToolResultStartEvent start)
                                        starts.add(start.getToolCallId());
                                });
        try {
            assertTrue(subscribed.await(3, TimeUnit.SECONDS));
            assertEquals(
                    List.of("slow-call", "fast-call"),
                    starts,
                    "Start events keep their original batch timing");
        } finally {
            subscription.dispose();
            agent.close();
        }
    }

    @Test
    void actionObservationFailureDoesNotRepeatResultOrCommitBatch() throws Exception {
        Sinks.One<ToolResultBlock> slow = Sinks.one();
        CountDownLatch fastEnd = new CountDownLatch(1);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool("slow", p -> slow.asMono()));
        toolkit.registerTool(tool("fast", p -> Mono.just(ToolResultBlock.text("keep me"))));
        ReActAgent agent =
                ReActAgent.builder().name("test").model(model()).toolkit(toolkit).build();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Disposable subscription =
                agent.streamEvents(List.of())
                        .subscribe(
                                e -> {
                                    events.add(e);
                                    if (e instanceof ToolResultEndEvent end
                                            && end.getToolCallId().equals("fast-call"))
                                        fastEnd.countDown();
                                },
                                e -> {
                                    failure.set(e);
                                    done.countDown();
                                },
                                done::countDown);
        try {
            assertTrue(fastEnd.await(3, TimeUnit.SECONDS));
            slow.tryEmitError(
                    new ActionObservationException(
                            new IllegalStateException("record unavailable")));
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertTrue(ActionObservationException.causedBy(failure.get()));
            assertEquals(
                    1,
                    events.stream()
                            .filter(
                                    e ->
                                            e instanceof ToolResultEndEvent end
                                                    && end.getToolCallId().equals("fast-call"))
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
            public String getName() {
                return name;
            }

            public String getDescription() {
                return name;
            }

            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            public Mono<ToolResultBlock> callAsync(ToolCallParam p) {
                return body.apply(p);
            }
        };
    }
}
