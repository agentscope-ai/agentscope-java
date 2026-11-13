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
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * The per-chunk interruption check is synchronous, and still stops the stream between two chunks.
 *
 * <p>The check used to be {@code checkInterrupted()} — a {@code Mono} wrapping one flag read — fed
 * through {@code concatMap}, so every streamed chunk crossed an asynchronous boundary to reach that
 * read. It now runs in a {@code handle} step, which keeps ordering and backpressure because it
 * processes elements one at a time. These tests pin what must not change when the wrapper goes away:
 * chunks reach the consumer in order, and an interrupt signalled while a chunk is being consumed
 * still stops the stream before the next one is delivered.
 */
class ReActAgentSyncInterruptTest {

    @Test
    void chunksReachTheConsumerInOrderWhenNothingInterrupts() {
        List<AgentEvent> events =
                run(new MultiChunkModel(List.of("one", "two", "three")), List.of());

        assertEquals(
                "onetwothree",
                deltas(events),
                "removing the per-chunk Mono must not reorder, drop or duplicate chunks");
    }

    @Test
    void interruptBetweenChunksStopsTheStreamAtTheChunkInFlight() {
        List<AgentEvent> events =
                run(
                        new MultiChunkModel(List.of("alpha", "beta", "gamma")),
                        List.of(new InterruptOnFirstChunk()));

        assertEquals(
                "alpha",
                deltas(events),
                "an interrupt must land before the next chunk, not after the whole response");
        assertTrue(
                events.stream()
                        .filter(AgentResultEvent.class::isInstance)
                        .map(AgentResultEvent.class::cast)
                        .anyMatch(
                                e ->
                                        e.getResult() != null
                                                && e.getResult().getGenerateReason()
                                                        == GenerateReason.INTERRUPTED),
                "the interrupted run must report INTERRUPTED");
    }

    @Test
    void interruptingAfterTheLastChunkDoesNotInventAChunk() {
        List<AgentEvent> events =
                run(new MultiChunkModel(List.of("only")), List.of(new InterruptOnFirstChunk()));

        assertEquals(
                "only",
                deltas(events),
                "an interrupt after delivery must not change what was already delivered");
    }

    private static List<AgentEvent> run(ChatModelBase model, List<MiddlewareBase> middlewares) {
        ReActAgent agent = buildAgent(model, middlewares);
        return agent.streamEvents(userMsg("go")).collectList().block(Duration.ofSeconds(10));
    }

    private static String deltas(List<AgentEvent> events) {
        StringBuilder text = new StringBuilder();
        for (AgentEvent event : events) {
            if (event instanceof TextBlockDeltaEvent delta) {
                text.append(delta.getDelta());
            }
        }
        return text.toString();
    }

    private static ReActAgent buildAgent(ChatModelBase model, List<MiddlewareBase> middlewares) {
        return ReActAgent.builder()
                .name("asst")
                .model(model)
                .toolkit(new Toolkit())
                .middlewares(middlewares)
                .build();
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /** Streams one {@link ChatResponse} per element, the way a model streams deltas. */
    private static final class MultiChunkModel extends ChatModelBase {
        private final List<String> chunks;

        MultiChunkModel(List<String> chunks) {
            this.chunks = chunks;
        }

        @Override
        public String getModelName() {
            return "multi-chunk";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.fromIterable(chunks)
                    .map(
                            chunk ->
                                    ChatResponse.builder()
                                            .content(
                                                    List.<ContentBlock>of(
                                                            TextBlock.builder()
                                                                    .text(chunk)
                                                                    .build()))
                                            .build());
        }
    }

    /** Interrupts the running call as soon as the first text delta reaches the consumer. */
    private static final class InterruptOnFirstChunk implements MiddlewareBase {
        private final AtomicBoolean fired = new AtomicBoolean();

        @Override
        public Flux<AgentEvent> onModelCall(
                Agent agent,
                RuntimeContext ctx,
                ModelCallInput input,
                Function<ModelCallInput, Flux<AgentEvent>> next) {
            return next.apply(input)
                    .map(
                            event -> {
                                if (event instanceof TextBlockDeltaEvent
                                        && fired.compareAndSet(false, true)) {
                                    if (agent instanceof ReActAgent react) {
                                        react.interrupt(ctx);
                                    }
                                }
                                return event;
                            });
        }
    }
}
