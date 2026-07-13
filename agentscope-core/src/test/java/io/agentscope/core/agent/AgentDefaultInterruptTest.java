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
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Backward-compatibility contract for the {@link Agent#interrupt(RuntimeContext)} and
 * {@link Agent#interrupt(RuntimeContext, Msg)} default methods.
 *
 * <p>Agent implementations that predate the session-aware overloads must continue to work: when
 * they do not override the new {@code interrupt(RuntimeContext, ...)} methods, calls MUST fall
 * through to the legacy {@link Agent#interrupt()} / {@link Agent#interrupt(Msg)} methods
 * unchanged.
 */
@DisplayName("Agent default interrupt(RuntimeContext) — legacy fallback")
class AgentDefaultInterruptTest {

    /**
     * A minimal {@link Agent} implementation that only overrides the legacy interrupt methods.
     * The two {@code interrupt(RuntimeContext, ...)} methods are inherited from the interface's
     * default implementations and must delegate here.
     */
    private static final class RecordingAgent implements Agent {
        final AtomicInteger legacyNoArgCalls = new AtomicInteger();
        final AtomicInteger legacyMsgCalls = new AtomicInteger();
        final AtomicReference<Msg> lastLegacyMsg = new AtomicReference<>();

        @Override
        public String getAgentId() {
            return "recording";
        }

        @Override
        public String getName() {
            return "recording";
        }

        @Override
        public void interrupt() {
            legacyNoArgCalls.incrementAndGet();
        }

        @Override
        public void interrupt(Msg msg) {
            legacyMsgCalls.incrementAndGet();
            lastLegacyMsg.set(msg);
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
        public Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
            return Mono.empty();
        }

        @Override
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
            return Flux.empty();
        }

        @Override
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
            return Flux.empty();
        }

        @Override
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema) {
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
    }

    @Test
    @DisplayName("interrupt(RuntimeContext) falls back to legacy interrupt() by default")
    void defaultInterruptWithContext_delegatesToLegacyNoArg() {
        RecordingAgent agent = new RecordingAgent();
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("sessA").build();

        agent.interrupt(ctx);

        assertEquals(1, agent.legacyNoArgCalls.get(), "default must delegate to interrupt()");
        assertEquals(0, agent.legacyMsgCalls.get(), "no-arg default must not touch interrupt(Msg)");
    }

    @Test
    @DisplayName("interrupt(RuntimeContext, Msg) falls back to legacy interrupt(Msg) by default")
    void defaultInterruptWithContextAndMsg_delegatesToLegacyWithMsg() {
        RecordingAgent agent = new RecordingAgent();
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("sessA").build();
        Msg attached =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("please stop").build())
                        .build();

        agent.interrupt(ctx, attached);

        assertEquals(0, agent.legacyNoArgCalls.get(), "msg default must not touch interrupt()");
        assertEquals(1, agent.legacyMsgCalls.get(), "default must delegate to interrupt(Msg)");
        assertSame(attached, agent.lastLegacyMsg.get(), "attached message must be forwarded");
    }

    @Test
    @DisplayName("null RuntimeContext still delegates to the legacy method")
    void defaultInterrupt_toleratesNullContext() {
        RecordingAgent agent = new RecordingAgent();

        agent.interrupt((RuntimeContext) null);
        agent.interrupt(null, null);

        assertEquals(1, agent.legacyNoArgCalls.get());
        assertEquals(1, agent.legacyMsgCalls.get());
    }
}
