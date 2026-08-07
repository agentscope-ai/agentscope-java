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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.interruption.InterruptSource;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Semantic contract of the {@link ReActAgent} overrides of
 * {@link io.agentscope.core.agent.Agent#interrupt(RuntimeContext)} and
 * {@link io.agentscope.core.agent.Agent#interrupt(RuntimeContext, Msg)}.
 *
 * <p>The interrupt signal must land on the {@link io.agentscope.core.interruption.InterruptControl}
 * belonging to the exact {@code (userId, sessionId)} slot carried by the {@link RuntimeContext},
 * and MUST NOT leak into other slots. When the context is {@code null} or its session id is
 * blank, the agent falls back to the configured {@code defaultSessionId}.
 */
@DisplayName("ReActAgent.interrupt(RuntimeContext) — per-session slot targeting")
class ReActAgentInterruptRuntimeContextTest {

    private static final class NoopModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "noop";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text("ok").build()))
                            .build());
        }
    }

    private ReActAgent agent() {
        return ReActAgent.builder()
                .name("asst")
                .sysPrompt("hi")
                .model(new NoopModel())
                .stateStore(new InMemoryAgentStateStore())
                .build();
    }

    @Test
    @DisplayName("interrupt(ctx) targets the (userId, sessionId) slot carried by the context")
    void interruptWithContext_targetsMatchingSlot() {
        ReActAgent agent = agent();
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("sessA").build();

        agent.interrupt(ctx);

        assertTrue(
                agent.getAgentState("u1", "sessA").interruptControl().isInterrupted(),
                "matching slot must observe the interrupt signal");
        assertFalse(
                agent.getAgentState("u1", "sessB").interruptControl().isInterrupted(),
                "sibling slot must NOT observe the signal");
        assertFalse(
                agent.getAgentState("u2", "sessA").interruptControl().isInterrupted(),
                "sibling user must NOT observe the signal");
    }

    @Test
    @DisplayName("interrupt(ctx, msg) attaches the user message on the correct slot")
    void interruptWithContextAndMsg_attachesMessage() {
        ReActAgent agent = agent();
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("sessA").build();
        Msg attached =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("please stop").build())
                        .build();

        agent.interrupt(ctx, attached);

        var control = agent.getAgentState("u1", "sessA").interruptControl();
        assertTrue(control.isInterrupted());
        assertSame(attached, control.getUserMessage(), "the attached msg must be recorded");
        assertSame(
                InterruptSource.USER,
                control.getSource(),
                "session-aware interrupt is a USER-source signal");
    }

    @Test
    @DisplayName("interrupt(null) falls back to the default session slot")
    void interruptWithNullContext_fallsBackToDefaultSession() {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model(new NoopModel())
                        .stateStore(new InMemoryAgentStateStore())
                        .defaultSessionId("fallback-slot")
                        .build();

        agent.interrupt((RuntimeContext) null);

        assertTrue(
                agent.getAgentState(null, "fallback-slot").interruptControl().isInterrupted(),
                "null ctx must land on the configured defaultSessionId slot");
    }

    @Test
    @DisplayName("interrupt(ctx) with blank sessionId falls back to the default session slot")
    void interruptWithBlankSessionId_fallsBackToDefault() {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model(new NoopModel())
                        .stateStore(new InMemoryAgentStateStore())
                        .defaultSessionId("fallback-slot")
                        .build();
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("   ").build();

        agent.interrupt(ctx);

        assertTrue(
                agent.getAgentState("u1", "fallback-slot").interruptControl().isInterrupted(),
                "blank sessionId must fall back to the configured defaultSessionId slot");
    }
}
