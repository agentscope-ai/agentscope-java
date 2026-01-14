/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.GenerateOptions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link StreamingHook}.
 *
 * <p>Focuses on THINKING / REASONING event routing and splitting logic.
 */
@DisplayName("StreamingHook Tests")
class StreamingHookTest {

    /**
     * Minimal AgentBase implementation used as the owner of hook events.
     */
    static class DummyAgent extends AgentBase {

        DummyAgent(String name) {
            super(name);
        }

        @Override
        protected Mono<Msg> doCall(List<Msg> msgs) {
            // Not used in these tests
            return Mono.empty();
        }

        @Override
        protected Mono<Void> doObserve(Msg msg) {
            // Not used in these tests
            return Mono.empty();
        }

        @Override
        protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
            // Not used in these tests
            return Mono.empty();
        }
    }

    private final DummyAgent dummyAgent = new DummyAgent("dummy-agent");
    private final GenerateOptions generateOptions = GenerateOptions.builder().build();

    @Test
    @DisplayName("Should emit THINKING event for ThinkingBlock chunk")
    void shouldEmitThinkingEventForThinkingChunk() {
        Msg incremental =
                Msg.builder()
                        .id("msg-thinking-1")
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("I think ").build())
                        .build();

        Msg accumulated =
                Msg.builder()
                        .id("msg-thinking-1")
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("I think step by step").build())
                        .build();

        HookEvent hookEvent =
                new ReasoningChunkEvent(
                        dummyAgent, "gpt-4", generateOptions, incremental, accumulated);

        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.THINKING, EventType.REASONING)
                        .incremental(true)
                        .includeReasoningChunk(true)
                        .includeReasoningResult(true)
                        .build();

        List<Event> events = new ArrayList<>();

        Flux.<Event>create(
                        (FluxSink<Event> sink) -> {
                            StreamingHook hook = new StreamingHook(sink, options);
                            hook.onEvent(hookEvent).block();
                            sink.complete();
                        })
                .doOnNext(events::add)
                .blockLast();

        assertEquals(1, events.size());
        Event thinkingEvent = events.get(0);
        assertEquals(EventType.THINKING, thinkingEvent.getType());
        assertTrue(thinkingEvent.getMessage().hasContentBlocks(ThinkingBlock.class));
        // Chunk event should not be marked as last
        assertTrue(!thinkingEvent.isLast());
    }

    @Test
    @DisplayName("Should route TextBlock chunk to REASONING event")
    void shouldRouteTextChunkToReasoningEvent() {
        Msg incremental =
                Msg.builder()
                        .id("msg-text-1")
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("hello").build())
                        .build();

        Msg accumulated =
                Msg.builder()
                        .id("msg-text-1")
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("hello world").build())
                        .build();

        HookEvent hookEvent =
                new ReasoningChunkEvent(
                        dummyAgent, "gpt-4", generateOptions, incremental, accumulated);

        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.THINKING, EventType.REASONING)
                        .incremental(true)
                        .includeReasoningChunk(true)
                        .includeReasoningResult(true)
                        .build();

        List<Event> events = new ArrayList<>();

        Flux.<Event>create(
                        sink -> {
                            StreamingHook hook = new StreamingHook(sink, options);
                            hook.onEvent(hookEvent).block();
                            sink.complete();
                        })
                .doOnNext(events::add)
                .blockLast();

        assertEquals(1, events.size());
        Event reasoningEvent = events.get(0);
        assertEquals(EventType.REASONING, reasoningEvent.getType());
        assertTrue(reasoningEvent.getMessage().hasContentBlocks(TextBlock.class));
        assertTrue(!reasoningEvent.isLast());
    }

    @Test
    @DisplayName("Should split PostReasoningEvent into THINKING and REASONING events")
    void shouldSplitThinkingAndReasoningInPostReasoningEvent() {
        Msg fullMsg =
                Msg.builder()
                        .id("msg-final-1")
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ThinkingBlock.builder()
                                                .thinking("internal thoughts")
                                                .build(),
                                        TextBlock.builder().text("final answer").build()))
                        .build();

        HookEvent hookEvent = new PostReasoningEvent(dummyAgent, "gpt-4", generateOptions, fullMsg);

        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.THINKING, EventType.REASONING)
                        .incremental(true)
                        .includeReasoningChunk(true)
                        .includeReasoningResult(true)
                        .build();

        List<Event> events = new ArrayList<>();

        Flux.<Event>create(
                        sink -> {
                            StreamingHook hook = new StreamingHook(sink, options);
                            hook.onEvent(hookEvent).block();
                            sink.complete();
                        })
                .doOnNext(events::add)
                .blockLast();

        // Expect one THINKING event and one REASONING event, both last
        assertEquals(2, events.size());

        Event thinkingEvent =
                events.stream()
                        .filter(e -> e.getType() == EventType.THINKING)
                        .findFirst()
                        .orElseThrow();
        Event reasoningEvent =
                events.stream()
                        .filter(e -> e.getType() == EventType.REASONING)
                        .findFirst()
                        .orElseThrow();

        assertTrue(thinkingEvent.getMessage().hasContentBlocks(ThinkingBlock.class));
        assertTrue(reasoningEvent.getMessage().hasContentBlocks(TextBlock.class));
        assertTrue(thinkingEvent.isLast());
        assertTrue(reasoningEvent.isLast());
    }
}
