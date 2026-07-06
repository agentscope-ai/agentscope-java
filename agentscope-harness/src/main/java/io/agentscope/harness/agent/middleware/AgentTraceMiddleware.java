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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Observability middleware that logs the reasoning and execution trace of an agent.
 *
 * <p>At INFO level, logs concise summaries: agent name, model, tool names/IDs, and
 * message lengths. At DEBUG level, additionally logs tool call arguments, tool result
 * content, reasoning text, and input message details.
 */
public class AgentTraceMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceMiddleware.class);

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (!log.isInfoEnabled()) {
            return next.apply(input);
        }
        String name = agent.getName();
        List<Msg> msgs = input.msgs();
        log.info("[{}] PRE_CALL  | {} input message(s)", name, msgs != null ? msgs.size() : 0);
        if (log.isDebugEnabled() && msgs != null) {
            for (Msg msg : msgs) {
                log.debug(
                        "[{}] PRE_CALL  |   [{}] {}",
                        name,
                        msg.getRole(),
                        truncate(msg.getTextContent(), 200));
            }
        }
        return next.apply(input)
                .doOnComplete(() -> logPostCall(agent, ctx))
                .doOnError(
                        e ->
                                log.info(
                                        "[{}] ERROR | {}: {}",
                                        name,
                                        e.getClass().getSimpleName(),
                                        e.getMessage()));
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (!log.isInfoEnabled()) {
            return next.apply(input);
        }
        String name = agent.getName();
        String modelName = resolveModelName(agent);
        int msgCount = input.messages() != null ? input.messages().size() : 0;
        log.info("[{}] PRE_REASONING  | model={}, messages={}", name, modelName, msgCount);
        if (log.isDebugEnabled() && input.messages() != null) {
            for (Msg msg : input.messages()) {
                log.debug(
                        "[{}] PRE_REASONING  |   [{}] len={}",
                        name,
                        msg.getRole(),
                        msg.getTextContent() != null ? msg.getTextContent().length() : 0);
            }
        }
        StringBuilder textBuf = new StringBuilder();
        List<ToolCallStartEvent> toolCalls = new ArrayList<>();
        return next.apply(input)
                .doOnNext(
                        ev -> {
                            if (ev instanceof TextBlockDeltaEvent tbd) {
                                if (tbd.getDelta() != null) {
                                    textBuf.append(tbd.getDelta());
                                }
                            } else if (ev instanceof ToolCallStartEvent tcs) {
                                toolCalls.add(tcs);
                            }
                        })
                .doOnComplete(
                        () -> {
                            String text = textBuf.toString();
                            boolean hasText = !text.isBlank();
                            // Always surface the model's text, even when it accompanies tool calls
                            // (a tool-call turn often carries a "thinking out loud" preamble that
                            // would otherwise be silently dropped).
                            if (hasText) {
                                log.info(
                                        "[{}] POST_REASONING | text: {}",
                                        name,
                                        truncate(text, 120));
                            }
                            if (toolCalls.isEmpty()) {
                                // No tool call ends the ReAct loop. If there was also no text, the
                                // model returned an empty completion — make that explicit instead
                                // of logging a misleading "<empty>" that looks like normal output.
                                if (!hasText) {
                                    log.info(
                                            "[{}] POST_REASONING | empty completion (no text, no"
                                                    + " tool call) — ReAct loop will terminate",
                                            name);
                                }
                            } else {
                                for (ToolCallStartEvent tc : toolCalls) {
                                    log.info(
                                            "[{}] POST_REASONING | tool_call: id={}, name={}",
                                            name,
                                            tc.getToolCallId(),
                                            tc.getToolCallName());
                                }
                            }
                        });
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        if (!log.isInfoEnabled()) {
            return next.apply(input);
        }
        String name = agent.getName();
        if (input.toolCalls() != null) {
            for (ToolUseBlock tu : input.toolCalls()) {
                log.info("[{}] PRE_ACTING  | id={}, name={}", name, tu.getId(), tu.getName());
                if (log.isDebugEnabled()) {
                    log.debug(
                            "[{}] PRE_ACTING  |   args={}",
                            name,
                            truncate(mapToJson(tu.getInput()), 500));
                }
            }
        }
        // Derive POST_ACTING from the tool-result events flowing through the middleware stream
        // (ToolResultStart/TextDelta/End), rather than scanning the context tail on completion.
        // The context append happens after this stream completes, so the previous approach missed
        // successfully-executed tools; observing the events captures every tool deterministically
        // and keeps us off the deprecated hook path.
        Map<String, String> toolNames = new ConcurrentHashMap<>();
        Map<String, StringBuilder> toolText = new ConcurrentHashMap<>();
        return next.apply(input)
                .doOnNext(
                        ev -> {
                            if (ev instanceof ToolResultStartEvent start) {
                                toolNames.put(start.getToolCallId(), start.getToolCallName());
                                toolText.computeIfAbsent(
                                        start.getToolCallId(), k -> new StringBuilder());
                            } else if (ev instanceof ToolResultTextDeltaEvent delta) {
                                if (delta.getDelta() != null) {
                                    toolText.computeIfAbsent(
                                                    delta.getToolCallId(), k -> new StringBuilder())
                                            .append(delta.getDelta());
                                }
                            } else if (ev instanceof ToolResultEndEvent end) {
                                String id = end.getToolCallId();
                                String toolName = toolNames.getOrDefault(id, "<unknown>");
                                String text =
                                        toolText.getOrDefault(id, new StringBuilder()).toString();
                                log.info(
                                        "[{}] POST_ACTING | id={}, name={}, result_len={},"
                                                + " state={}",
                                        name,
                                        id,
                                        toolName,
                                        text.length(),
                                        end.getState());
                                if (log.isDebugEnabled()) {
                                    log.debug(
                                            "[{}] POST_ACTING |   result={}",
                                            name,
                                            truncate(text, 500));
                                }
                            }
                        });
    }

    private void logPostCall(Agent agent, RuntimeContext rc) {
        String name = agent.getName();
        AgentState state = RuntimeContext.resolveAgentState(rc, agent);
        if (state == null) {
            log.info("[{}] POST_CALL | response: <n/a>", name);
            return;
        }
        Msg lastAssistant = null;
        List<Msg> ctx = state.getContext();
        for (int i = ctx.size() - 1; i >= 0; i--) {
            if (ctx.get(i).getRole() == MsgRole.ASSISTANT) {
                lastAssistant = ctx.get(i);
                break;
            }
        }
        if (lastAssistant == null) {
            log.info("[{}] POST_CALL | response: <n/a>", name);
            return;
        }
        String text = lastAssistant.getTextContent();
        // A turn carrying tool calls is never a clean final reply: if it is the last
        // assistant message, the loop ended on a tool-call turn (e.g., the model returned an
        // empty completion right after). Surfacing its preamble as the "response" is misleading,
        // so we flag the situation explicitly and show the preamble only as context.
        boolean endedOnToolCall = !lastAssistant.getContentBlocks(ToolUseBlock.class).isEmpty();
        if (endedOnToolCall) {
            log.info(
                    "[{}] POST_CALL | ended on a tool-call turn with no final text reply; last"
                            + " preamble: {}",
                    name,
                    truncate(text, 120));
        } else {
            log.info("[{}] POST_CALL | response: {}", name, truncate(text, 120));
        }
    }

    private static String resolveModelName(Agent agent) {
        if (agent instanceof ReActAgent r && r.getModel() != null) {
            return r.getModel().getModelName();
        }
        return "<unknown>";
    }

    private static String truncate(String s, int max) {
        if (s == null || s.isEmpty()) {
            return "<empty>";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...[truncated, limit=" + max + " chars]";
    }

    private static String mapToJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            return JsonUtils.getJsonCodec().toJson(map);
        } catch (Exception e) {
            return map.toString();
        }
    }
}
