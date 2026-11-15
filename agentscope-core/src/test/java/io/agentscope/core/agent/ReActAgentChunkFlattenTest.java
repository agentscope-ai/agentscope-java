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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The streamed chunk path must keep generating events synchronously per chunk: each chunk drains
 * into the event sequence through {@code concatMapIterable} instead of subscribing an inner Flux
 * per chunk, so ordering must stay exact across many chunks and hook delivery must still see the
 * subscriber context captured once for the whole stream (issue #3929, third measured change).
 */
class ReActAgentChunkFlattenTest {

    private static final class ScriptedModel extends ChatModelBase {
        private final List<Supplier<Flux<ChatResponse>>> scripts;
        private final AtomicInteger idx = new AtomicInteger(0);

        ScriptedModel(List<Supplier<Flux<ChatResponse>>> scripts) {
            this.scripts = scripts;
        }

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int i = idx.getAndIncrement();
            if (i >= scripts.size()) {
                return Flux.just(textResponse(""));
            }
            return scripts.get(i).get();
        }
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.<ContentBlock>of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static ChatResponse thinkingResponse(String thinking) {
        return ChatResponse.builder()
                .content(List.<ContentBlock>of(ThinkingBlock.builder().thinking(thinking).build()))
                .build();
    }

    @Test
    @DisplayName("deltas from many streamed chunks reach the subscriber in model order")
    void streamedChunkDeltasKeepModelOrderAcrossManyChunks() {
        List<ChatResponse> responses = new ArrayList<>();
        responses.add(thinkingResponse("plan"));
        for (int i = 0; i < 30; i++) {
            responses.add(textResponse("t" + i));
        }
        ScriptedModel model = new ScriptedModel(List.of(() -> Flux.fromIterable(responses)));
        ReActAgent agent = ReActAgent.builder().name("asst").model(model).build();

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events, "stream must complete with events");

        int firstTextIdx = -1;
        int thinkingIdx = -1;
        List<String> deltas = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            AgentEvent event = events.get(i);
            if (event instanceof ThinkingBlockDeltaEvent && thinkingIdx < 0) {
                thinkingIdx = i;
            }
            if (event instanceof TextBlockDeltaEvent delta) {
                if (firstTextIdx < 0) {
                    firstTextIdx = i;
                }
                deltas.add(delta.getDelta());
            }
        }

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            expected.add("t" + i);
        }
        assertEquals(expected, deltas, "text deltas must arrive in model order, none lost");

        assertTrue(thinkingIdx >= 0, "the thinking chunk must produce a thinking delta");
        assertTrue(
                firstTextIdx > thinkingIdx, "the thinking delta must precede the first text delta");
        assertTrue(
                events.stream().anyMatch(e -> e instanceof ModelCallStartEvent),
                "model call start must be present");
        assertTrue(
                events.stream().anyMatch(e -> e instanceof ModelCallEndEvent),
                "model call end must be present");
    }

    @Test
    @DisplayName("a hook still sees subscriber context written at subscription time")
    void hookStillSeesSubscriberContextCapturedOnce() {
        AtomicReference<String> seen = new AtomicReference<>("absent");
        Hook hook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof ReasoningChunkEvent) {
                            return Mono.deferContextual(
                                    ctx -> {
                                        seen.set(ctx.getOrDefault("chunkFlattenProbe", "absent"));
                                        return Mono.just(event);
                                    });
                        }
                        return Mono.just(event);
                    }
                };

        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(textResponse("a"), textResponse("b"))));
        ReActAgent agent =
                ReActAgent.builder().name("asst").model(model).hooks(List.of(hook)).build();

        List<AgentEvent> events =
                agent.streamEvents(List.of())
                        .contextWrite(ctx -> ctx.put("chunkFlattenProbe", "present"))
                        .collectList()
                        .block();
        assertNotNull(events, "stream must complete with events");

        assertEquals(
                "present",
                seen.get(),
                "hook delivery must inherit the context written downstream at subscription"
                        + " time");
    }
}
