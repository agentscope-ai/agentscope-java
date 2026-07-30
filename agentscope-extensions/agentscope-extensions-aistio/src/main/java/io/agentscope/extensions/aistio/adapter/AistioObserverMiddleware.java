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
package io.agentscope.extensions.aistio.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.extensions.aistio.model.SessionEvent;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Middleware that copies conversation activity out to aistio without touching it.
 *
 * <p>Purely additive: inputs are forwarded to {@code next} unchanged and the resulting event stream
 * is only tapped, never rewritten or filtered. Removing this middleware changes nothing about how
 * the agent behaves.
 *
 * <p>Tool call arguments are read in {@link #onActing}, because {@code ToolCallEndEvent} carries
 * only the call's name and id. Tool output text is reassembled from the delta events, which is the
 * only place the framework exposes it incrementally.
 */
public final class AistioObserverMiddleware implements MiddlewareBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentScopeAdapter adapter;
    private final Set<String> startedSessions = ConcurrentHashMap.newKeySet();

    AistioObserverMiddleware(AgentScopeAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        String sessionId = sessionIdOf(ctx, agent);
        adapter.rememberSession(sessionId, ctx == null ? null : ctx.getUserId(), agent);

        // One call() is a turn, not a session; the session opens on the first turn and closes
        // only on an explicit terminate, so no session_end is emitted here.
        if (startedSessions.add(sessionId)) {
            adapter.publish(SessionEvent.builder(sessionId, SessionEvent.SESSION_START).build());
        }

        if (input != null && input.msgs() != null) {
            for (Msg msg : input.msgs()) {
                String text = AgentScopeAdapter.textOf(msg);
                if (!text.isEmpty()) {
                    adapter.publish(
                            SessionEvent.builder(sessionId, SessionEvent.MESSAGE)
                                    .role(AgentScopeAdapter.roleOf(msg))
                                    .content(text)
                                    .build());
                }
            }
        }

        TurnState turn = new TurnState();
        adapter.markBusy(sessionId, true);
        return next.apply(input)
                .doOnNext(event -> observe(sessionId, turn, event))
                .doFinally(signal -> adapter.markBusy(sessionId, false));
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        String sessionId = sessionIdOf(ctx, agent);
        if (input != null && input.toolCalls() != null) {
            for (ToolUseBlock call : input.toolCalls()) {
                adapter.publish(
                        SessionEvent.builder(sessionId, SessionEvent.TOOL_CALL)
                                .role(SessionEvent.ROLE_ASSISTANT)
                                .toolName(call.getName())
                                .toolInputJson(toJson(call.getInput()))
                                .build());
            }
        }
        return next.apply(input);
    }

    /**
     * Captures the fully composed system prompt when this middleware is registered last in the
     * {@code onSystemPrompt} chain (paw wires aistio after other middlewares).
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String sessionId = sessionIdOf(ctx, agent);
        adapter.recordEffectiveSystemPrompt(sessionId, currentPrompt);
        return Mono.just(currentPrompt == null ? "" : currentPrompt);
    }

    private void observe(String sessionId, TurnState turn, AgentEvent event) {
        if (event instanceof ModelCallEndEvent modelCall) {
            ChatUsage usage = modelCall.getUsage();
            if (usage != null) {
                turn.tokensIn.addAndGet(usage.getInputTokens());
                turn.tokensOut.addAndGet(usage.getOutputTokens());
            }
        } else if (event instanceof ToolResultTextDeltaEvent delta) {
            turn.toolOutputs
                    .computeIfAbsent(delta.getToolCallId(), id -> new StringBuilder())
                    .append(delta.getDelta());
        } else if (event instanceof ToolResultEndEvent end) {
            StringBuilder output = turn.toolOutputs.remove(end.getToolCallId());
            adapter.publish(
                    SessionEvent.builder(sessionId, SessionEvent.TOOL_RESULT)
                            .role(SessionEvent.ROLE_TOOL)
                            .toolName(end.getToolCallName())
                            .toolOutput(output == null ? "" : output.toString())
                            .build());
        } else if (event instanceof AgentResultEvent result) {
            Msg msg = result.getResult();
            String text = AgentScopeAdapter.textOf(msg);
            if (!text.isEmpty()) {
                // Token usage is reported once per turn, on the message it produced.
                adapter.publish(
                        SessionEvent.builder(sessionId, SessionEvent.MESSAGE)
                                .role(SessionEvent.ROLE_ASSISTANT)
                                .content(text)
                                .tokens(turn.tokensIn.getAndSet(0), turn.tokensOut.getAndSet(0))
                                .build());
            }
        }
    }

    private static String sessionIdOf(RuntimeContext ctx, Agent agent) {
        String sessionId = ctx == null ? null : ctx.getSessionId();
        if (sessionId != null && !sessionId.isEmpty()) {
            return sessionId;
        }
        return agent == null ? "default" : agent.getAgentId();
    }

    private static String toJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** Per-turn accumulators for data the framework only exposes incrementally. */
    private static final class TurnState {
        private final AtomicInteger tokensIn = new AtomicInteger();
        private final AtomicInteger tokensOut = new AtomicInteger();
        private final Map<String, StringBuilder> toolOutputs = new ConcurrentHashMap<>();
    }
}
