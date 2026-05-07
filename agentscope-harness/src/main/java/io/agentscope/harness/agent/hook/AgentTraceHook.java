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
package io.agentscope.harness.agent.hook;

import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.PreSummaryEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Observability hook that logs the complete reasoning and execution trace of an agent.
 *
 * <p>At INFO level, logs concise summaries: event type, agent name, tool names/IDs, and
 * message lengths. At DEBUG level, additionally logs tool call arguments, tool result content,
 * reasoning text, and input message details.
 *
 * <p>This hook runs at the lowest priority (first in, last out) so it captures all events
 * without interfering with other hooks.
 */
public class AgentTraceHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceHook.class);

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!log.isInfoEnabled()) {
            return Mono.just(event);
        }

        String agent = event.getAgent().getName();

        if (event instanceof PreCallEvent e) {
            logPreCall(agent, e);
        } else if (event instanceof PostCallEvent e) {
            logPostCall(agent, e);
        } else if (event instanceof PreReasoningEvent e) {
            logPreReasoning(agent, e);
        } else if (event instanceof PostReasoningEvent e) {
            logPostReasoning(agent, e);
        } else if (event instanceof PreActingEvent e) {
            logPreActing(agent, e);
        } else if (event instanceof PostActingEvent e) {
            logPostActing(agent, e);
        } else if (event instanceof PreSummaryEvent e) {
            logPreSummary(agent, e);
        } else if (event instanceof ErrorEvent e) {
            logError(agent, e);
        }

        return Mono.just(event);
    }

    private void logPreCall(String agent, PreCallEvent e) {
        List<Msg> msgs = e.getInputMessages();
        log.info("[{}] PRE_CALL  | {} input message(s)", agent, msgs != null ? msgs.size() : 0);
        if (log.isDebugEnabled() && msgs != null) {
            for (Msg msg : msgs) {
                log.debug(
                        "[{}] PRE_CALL  |   [{}] {}",
                        agent,
                        msg.getRole(),
                        truncate(msg.getTextContent(), 200));
            }
        }
    }

    private void logPostCall(String agent, PostCallEvent e) {
        Msg msg = e.getFinalMessage();
        String preview = msg != null ? truncate(msg.getTextContent(), 120) : "<null>";
        log.info("[{}] POST_CALL | response: {}", agent, preview);
    }

    private void logPreReasoning(String agent, PreReasoningEvent e) {
        int msgCount = e.getInputMessages() != null ? e.getInputMessages().size() : 0;
        log.info("[{}] PRE_REASONING  | model={}, messages={}", agent, e.getModelName(), msgCount);
        if (log.isDebugEnabled() && e.getInputMessages() != null) {
            for (Msg msg : e.getInputMessages()) {
                log.debug(
                        "[{}] PRE_REASONING  |   [{}] len={}",
                        agent,
                        msg.getRole(),
                        msg.getTextContent() != null ? msg.getTextContent().length() : 0);
            }
        }
    }

    private void logPostReasoning(String agent, PostReasoningEvent e) {
        Msg msg = e.getReasoningMessage();
        if (msg == null) {
            log.info("[{}] POST_REASONING | <no message>", agent);
            return;
        }
        List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
        if (toolCalls.isEmpty()) {
            String text = truncate(msg.getTextContent(), 120);
            log.info("[{}] POST_REASONING | text response: {}", agent, text);
        } else {
            for (ToolUseBlock tu : toolCalls) {
                log.info(
                        "[{}] POST_REASONING | tool_call: id={}, name={}",
                        agent,
                        tu.getId(),
                        tu.getName());
                if (log.isDebugEnabled()) {
                    log.debug(
                            "[{}] POST_REASONING |   args={}",
                            agent,
                            truncate(mapToJson(tu.getInput()), 500));
                }
            }
        }
    }

    private void logPreActing(String agent, PreActingEvent e) {
        ToolUseBlock tu = e.getToolUse();
        if (tu == null) {
            return;
        }
        log.info("[{}] PRE_ACTING  | id={}, name={}", agent, tu.getId(), tu.getName());
        if (log.isDebugEnabled()) {
            log.debug(
                    "[{}] PRE_ACTING  |   args={}", agent, truncate(mapToJson(tu.getInput()), 500));
        }
    }

    private void logPostActing(String agent, PostActingEvent e) {
        ToolUseBlock tu = e.getToolUse();
        ToolResultBlock tr = e.getToolResult();
        String toolName = tu != null ? tu.getName() : "?";
        String toolId = tu != null ? tu.getId() : "?";
        int resultLen = toolResultLength(tr);
        log.info(
                "[{}] POST_ACTING | id={}, name={}, result_len={}",
                agent,
                toolId,
                toolName,
                resultLen);
        if (log.isDebugEnabled() && tr != null) {
            log.debug("[{}] POST_ACTING |   result={}", agent, truncate(toolResultText(tr), 500));
        }
    }

    private void logPreSummary(String agent, PreSummaryEvent e) {
        log.info(
                "[{}] PRE_SUMMARY | model={}, iter={}/{}",
                agent,
                e.getModelName(),
                e.getCurrentIteration(),
                e.getMaxIterations());
    }

    private void logError(String agent, ErrorEvent e) {
        Throwable err = e.getError();
        log.info("[{}] ERROR | {}: {}", agent, err.getClass().getSimpleName(), err.getMessage());
        if (log.isDebugEnabled()) {
            log.debug("[{}] ERROR | stacktrace:", agent, err);
        }
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
