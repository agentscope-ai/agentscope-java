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
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.tool.Toolkit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Tests for {@link ReActAgent#handleInterrupt} reconciling a dangling {@link ToolUseBlock} when an
 * interrupt is signalled between reasoning (tool_use emitted) and acting (tool executed). See
 * issue #2409.
 */
class ReActAgentInterruptToolCallTest {

    private static final class ScriptedModel extends ChatModelBase {
        private final List<Supplier<Flux<ChatResponse>>> scripts;
        private final AtomicInteger idx = new AtomicInteger();

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

    private static ChatResponse toolUseResponse(String toolId, String toolName) {
        Map<String, Object> input = new HashMap<>();
        input.put("query", "x");
        return ChatResponse.builder()
                .content(
                        List.<ContentBlock>of(
                                ToolUseBlock.builder()
                                        .id(toolId)
                                        .name(toolName)
                                        .input(input)
                                        .build()))
                .build();
    }

    /**
     * Interrupts the agent right after the first model call completes, simulating a post-generation
     * resource check (e.g. quota/budget exhaustion): the model has emitted a {@link ToolUseBlock},
     * but the agent is interrupted before acting executes it.
     */
    private static final class InterruptAfterModelCallMiddleware implements MiddlewareBase {
        private final AtomicBoolean fired = new AtomicBoolean();

        @Override
        public Flux<AgentEvent> onModelCall(
                Agent agent,
                RuntimeContext ctx,
                ModelCallInput input,
                Function<ModelCallInput, Flux<AgentEvent>> next) {
            return next.apply(input)
                    .concatMap(
                            event -> {
                                if (event instanceof ModelCallEndEvent
                                        && fired.compareAndSet(false, true)) {
                                    if (agent instanceof ReActAgent r) {
                                        r.interrupt(ctx);
                                    } else {
                                        agent.interrupt();
                                    }
                                }
                                return Flux.just(event);
                            });
        }
    }

    private static ReActAgent buildAgent(ChatModelBase model, MiddlewareBase... middlewares) {
        return ReActAgent.builder()
                .name("asst")
                .model(model)
                .toolkit(new Toolkit())
                .middlewares(List.of(middlewares))
                .build();
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    @Test
    void interruptAfterToolUseSynthesizesErrorResultAndKeepsContextConsistent() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc1", "search")),
                                () -> Flux.just(textResponse("resumed"))));
        ReActAgent agent = buildAgent(model, new InterruptAfterModelCallMiddleware());

        Msg result = agent.call(List.of(userMsg("do something"))).block();
        assertNotNull(result);
        assertEquals(GenerateReason.INTERRUPTED, result.getGenerateReason());

        List<Msg> context = agent.getAgentState().getContext();
        List<ToolUseBlock> toolUses =
                context.stream()
                        .flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
                        .toList();
        List<ToolResultBlock> toolResults =
                context.stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .toList();

        assertEquals(1, toolUses.size(), "one tool_use should have been emitted");
        assertEquals(
                1,
                toolResults.size(),
                "an error result must be synthesized for the pending tool_use");
        assertEquals(
                toolUses.get(0).getId(),
                toolResults.get(0).getId(),
                "synthesized result must match the pending tool_use id");
        assertEquals(
                ToolResultState.ERROR,
                toolResults.get(0).getState(),
                "synthesized result must be ERROR");

        Msg lastAssistant = null;
        for (int i = context.size() - 1; i >= 0; i--) {
            if (context.get(i).getRole() == MsgRole.ASSISTANT) {
                lastAssistant = context.get(i);
                break;
            }
        }
        assertNotNull(lastAssistant, "a recovery assistant message must exist");
        assertTrue(
                lastAssistant.getContentBlocks(ToolUseBlock.class).isEmpty(),
                "the last assistant message must be the recovery message, not the tool_use");
        assertEquals(GenerateReason.INTERRUPTED, lastAssistant.getGenerateReason());
    }

    @Test
    void resumedRunAfterInterruptRepliesNormally() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc1", "search")),
                                () -> Flux.just(textResponse("resumed-ok"))));
        ReActAgent agent = buildAgent(model, new InterruptAfterModelCallMiddleware());

        Msg first = agent.call(List.of(userMsg("do something"))).block();
        assertNotNull(first);
        assertEquals(GenerateReason.INTERRUPTED, first.getGenerateReason());

        Msg second = agent.call(List.of(userMsg("continue"))).block();
        assertNotNull(second);
        assertEquals("resumed-ok", second.getTextContent());
    }

    /**
     * A user interrupt after the model emits a tool_use must be reconciled (error tool result
     * synthesized) and persisted exactly once via {@link ReActAgent#handleInterrupt}. The
     * call-failure persistence hook must NOT race ahead of that reconciliation and persist an
     * unreconciled dangling tool_use. Regression test for the saveStateAfterCallFailure /
     * InterruptedException interaction.
     */
    @Test
    void interruptPersistsOnceWithReconciledToolResult() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc1", "search")),
                                () -> Flux.just(textResponse("resumed"))));
        CountingStateStore store = new CountingStateStore();
        ReActAgent agent =
                buildAgentWithStore(model, store, new InterruptAfterModelCallMiddleware());

        Msg result = agent.call(List.of(userMsg("do something"))).block();
        assertNotNull(result);
        assertEquals(GenerateReason.INTERRUPTED, result.getGenerateReason());

        assertEquals(
                1,
                store.saveIfVersionCalls.get(),
                "interrupt must persist exactly once via handleInterrupt, not preemptively"
                        + " before reconciliation");

        AgentState persisted =
                store.get(null, agent.getDefaultSessionId(), "agent_state", AgentState.class)
                        .orElseThrow();
        List<ToolUseBlock> toolUses =
                persisted.getContext().stream()
                        .flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
                        .toList();
        List<ToolResultBlock> toolResults =
                persisted.getContext().stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .toList();
        assertEquals(1, toolUses.size(), "the emitted tool_use must be persisted");
        assertEquals(
                1,
                toolResults.size(),
                "a matching error tool result must be reconciled for the pending tool_use");
        assertEquals(toolUses.get(0).getId(), toolResults.get(0).getId());
        assertEquals(ToolResultState.ERROR, toolResults.get(0).getState());
    }

    private static ReActAgent buildAgentWithStore(
            ChatModelBase model, InMemoryAgentStateStore store, MiddlewareBase... middlewares) {
        return ReActAgent.builder()
                .name("asst")
                .model(model)
                .toolkit(new Toolkit())
                .middlewares(List.of(middlewares))
                .stateStore(store)
                .build();
    }

    private static final class CountingStateStore extends InMemoryAgentStateStore {
        final AtomicInteger saveIfVersionCalls = new AtomicInteger();

        @Override
        public long saveIfVersion(
                String userId, String sessionId, String key, State value, long expectedVersion) {
            saveIfVersionCalls.incrementAndGet();
            return super.saveIfVersion(userId, sessionId, key, value, expectedVersion);
        }
    }
}
