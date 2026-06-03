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
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
public class AgentTraceMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceMiddleware.class);

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent, AgentInput input, Function<AgentInput, Flux<AgentEvent>> next) {
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
                .doOnComplete(() -> logPostCall(agent))
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
            Agent agent, ReasoningInput input, Function<ReasoningInput, Flux<AgentEvent>> next) {
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
                            if (toolCalls.isEmpty()) {
                                log.info(
                                        "[{}] POST_REASONING | text response: {}",
                                        name,
                                        truncate(textBuf.toString(), 120));
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
            Agent agent, ActingInput input, Function<ActingInput, Flux<AgentEvent>> next) {
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
        return next.apply(input).doOnComplete(() -> logPostActingFromState(agent));
    }

    private void logPostActingFromState(Agent agent) {
        AgentState state = agent.getAgentState();
        if (state == null) {
            return;
        }
        String name = agent.getName();
        List<Msg> ctx = state.getContext();
        for (int i = ctx.size() - 1; i >= 0; i--) {
            Msg m = ctx.get(i);
            if (m.getRole() != MsgRole.TOOL) {
                break;
            }
            for (ToolResultBlock tr : m.getContentBlocks(ToolResultBlock.class)) {
                log.info(
                        "[{}] POST_ACTING | id={}, name={}, result_len={}",
                        name,
                        tr.getId(),
                        tr.getName(),
                        toolResultLength(tr));
                if (log.isDebugEnabled()) {
                    log.debug(
                            "[{}] POST_ACTING |   result={}",
                            name,
                            truncate(toolResultText(tr), 500));
                }
            }
        }
    }

    private void logPostCall(Agent agent) {
        String name = agent.getName();
        AgentState state = agent.getAgentState();
        String preview = "<n/a>";
        if (state != null) {
            List<Msg> ctx = state.getContext();
            for (int i = ctx.size() - 1; i >= 0; i--) {
                Msg m = ctx.get(i);
                if (m.getRole() == MsgRole.ASSISTANT) {
                    preview = truncate(m.getTextContent(), 120);
                    break;
                }
            }
        }
        log.info("[{}] POST_CALL | response: {}", name, preview);
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

    private static int toolResultLength(ToolResultBlock tr) {
        if (tr == null || tr.getOutput() == null) {
            return 0;
        }
        int len = 0;
        for (ContentBlock block : tr.getOutput()) {
            if (block instanceof TextBlock tb && tb.getText() != null) {
                len += tb.getText().length();
            }
        }
        return len;
    }

    private static String toolResultText(ToolResultBlock tr) {
        if (tr == null || tr.getOutput() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : tr.getOutput()) {
            if (block instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }
}
