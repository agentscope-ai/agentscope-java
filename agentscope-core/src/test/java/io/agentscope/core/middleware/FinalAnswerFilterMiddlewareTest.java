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

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.model.ChatUsage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class FinalAnswerFilterMiddlewareTest {

    private static final String REPLY_ID = "reply-1";

    private final FinalAnswerFilterMiddleware middleware = new FinalAnswerFilterMiddleware();

    @Test
    void finalRoundEmitsBufferedTextBeforeModelCallEnd() {
        List<AgentEvent> events =
                apply(
                        Flux.just(
                                new ModelCallStartEvent(REPLY_ID),
                                new TextBlockStartEvent(REPLY_ID, "text"),
                                new TextBlockDeltaEvent(REPLY_ID, "text", "final answer"),
                                new TextBlockEndEvent(REPLY_ID, "text"),
                                new ModelCallEndEvent(REPLY_ID, (ChatUsage) null)));

        assertEquals(5, events.size());
        assertTrue(events.get(0) instanceof ModelCallStartEvent);
        assertTrue(events.get(1) instanceof TextBlockStartEvent);
        assertTrue(events.get(2) instanceof TextBlockDeltaEvent);
        assertTrue(events.get(3) instanceof TextBlockEndEvent);
        assertTrue(events.get(4) instanceof ModelCallEndEvent);
    }

    @Test
    void intermediateRoundSuppressesTextWhenToolCallIsObserved() {
        List<AgentEvent> events =
                apply(
                        Flux.just(
                                new ModelCallStartEvent(REPLY_ID),
                                new TextBlockStartEvent(REPLY_ID, "text"),
                                new TextBlockDeltaEvent(REPLY_ID, "text", "intermediate"),
                                new TextBlockEndEvent(REPLY_ID, "text"),
                                new ToolCallStartEvent(REPLY_ID, "tool-1", "search"),
                                new ModelCallEndEvent(REPLY_ID, (ChatUsage) null)));

        assertEquals(3, events.size());
        assertTrue(events.get(0) instanceof ModelCallStartEvent);
        assertTrue(events.get(1) instanceof ToolCallStartEvent);
        assertTrue(events.get(2) instanceof ModelCallEndEvent);
        assertFalse(events.stream().anyMatch(TextBlockStartEvent.class::isInstance));
        assertFalse(events.stream().anyMatch(TextBlockDeltaEvent.class::isInstance));
        assertFalse(events.stream().anyMatch(TextBlockEndEvent.class::isInstance));
    }

    @Test
    void nonTextEventsAreForwarded() {
        ThinkingBlockDeltaEvent thinking =
                new ThinkingBlockDeltaEvent(REPLY_ID, "thinking", "reasoning");
        ToolCallStartEvent toolCall = new ToolCallStartEvent(REPLY_ID, "tool-1", "search");

        List<AgentEvent> events =
                apply(
                        Flux.just(
                                new ModelCallStartEvent(REPLY_ID),
                                thinking,
                                new TextBlockDeltaEvent(REPLY_ID, "text", "intermediate"),
                                toolCall,
                                new ModelCallEndEvent(REPLY_ID, (ChatUsage) null)));

        assertTrue(events.contains(thinking));
        assertTrue(events.contains(toolCall));
    }

    @Test
    void stateIsolatedAcrossSubscriptions() {
        AtomicInteger subscriptionCount = new AtomicInteger();
        Flux<AgentEvent> events =
                middleware.onReasoning(
                        null,
                        null,
                        new ReasoningInput(List.of(), List.of(), null),
                        ignored ->
                                Flux.defer(
                                        () -> {
                                            if (subscriptionCount.getAndIncrement() == 0) {
                                                return Flux.just(
                                                        new ModelCallStartEvent(REPLY_ID),
                                                        new TextBlockDeltaEvent(
                                                                REPLY_ID, "text", "intermediate"),
                                                        new ToolCallStartEvent(
                                                                REPLY_ID, "tool-1", "search"),
                                                        new ModelCallEndEvent(
                                                                REPLY_ID, (ChatUsage) null));
                                            }
                                            return Flux.just(
                                                    new ModelCallStartEvent(REPLY_ID),
                                                    new TextBlockDeltaEvent(
                                                            REPLY_ID, "text", "final answer"),
                                                    new ModelCallEndEvent(
                                                            REPLY_ID, (ChatUsage) null));
                                        }));

        List<AgentEvent> first = events.collectList().block();
        List<AgentEvent> second = events.collectList().block();

        assertFalse(first.stream().anyMatch(TextBlockDeltaEvent.class::isInstance));
        assertTrue(
                second.stream()
                        .filter(TextBlockDeltaEvent.class::isInstance)
                        .map(TextBlockDeltaEvent.class::cast)
                        .anyMatch(event -> "final answer".equals(event.getDelta())));
    }

    private List<AgentEvent> apply(Flux<AgentEvent> source) {
        return middleware
                .onReasoning(
                        null,
                        null,
                        new ReasoningInput(List.of(), List.of(), null),
                        ignored -> source)
                .collectList()
                .block();
    }
}
