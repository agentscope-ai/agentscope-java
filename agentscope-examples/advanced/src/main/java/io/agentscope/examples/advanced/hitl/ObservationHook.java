/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.advanced.hitl;

import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Observation hook that logs agent conversation and tool I/O to the console.
 *
 * <p>Logs the following events with structured formatting:
 * <ul>
 *   <li>{@link PreCallEvent} — Agent invocation with input messages</li>
 *   <li>{@link PreReasoningEvent} — Messages sent to the LLM</li>
 *   <li>{@link PostReasoningEvent} — LLM response (text + tool calls)</li>
 *   <li>{@link PreActingEvent} — Tool invocation with parameters</li>
 *   <li>{@link PostActingEvent} — Tool execution result</li>
 *   <li>{@link PostCallEvent} — Final agent response</li>
 *   <li>{@link ErrorEvent} — Errors during execution</li>
 * </ul>
 */
public class ObservationHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ObservationHook.class);

    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String RESET = "\u001B[0m";

    private static final String SEPARATOR = DIM + "─".repeat(60) + RESET;

    @Override
    public int priority() {
        // Low priority — pure observation, runs after business hooks
        return 900;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent e) {
            logPreCall(e);
        } else if (event instanceof PreReasoningEvent e) {
            logPreReasoning(e);
        } else if (event instanceof PostReasoningEvent e) {
            logPostReasoning(e);
        } else if (event instanceof PreActingEvent e) {
            logPreActing(e);
        } else if (event instanceof PostActingEvent e) {
            logPostActing(e);
        } else if (event instanceof PostCallEvent e) {
            logPostCall(e);
        } else if (event instanceof ErrorEvent e) {
            logError(e);
        }
        return Mono.just(event);
    }

    private void logPreCall(PreCallEvent event) {
        List<Msg> inputs = event.getInputMessages();
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append(SEPARATOR).append('\n');
        sb.append(BOLD).append(CYAN).append("▶ AGENT CALL").append(RESET);
        sb.append(DIM)
                .append("  (")
                .append(inputs.size())
                .append(" input message(s))")
                .append(RESET);
        sb.append('\n').append(SEPARATOR);
        for (Msg msg : inputs) {
            sb.append('\n').append(formatMsg(msg));
        }
        log.info(sb.toString());
    }

    private void logPreReasoning(PreReasoningEvent event) {
        List<Msg> messages = event.getInputMessages();
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append(SEPARATOR).append('\n');
        sb.append(BOLD).append(CYAN).append("🧠 PRE-REASONING").append(RESET);
        sb.append(DIM)
                .append("  model=")
                .append(event.getModelName())
                .append(", messages=")
                .append(messages.size())
                .append(RESET);
        sb.append('\n').append(SEPARATOR);
        for (Msg msg : messages) {
            sb.append('\n').append(formatMsg(msg));
        }
        log.info(sb.toString());
    }

    private void logPostReasoning(PostReasoningEvent event) {
        Msg msg = event.getReasoningMessage();
        if (msg == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append('\n').append(SEPARATOR).append('\n');
        sb.append(BOLD).append(GREEN).append("🧠 POST-REASONING").append(RESET);
        sb.append('\n').append(SEPARATOR);
        sb.append('\n').append(formatMsg(msg));

        // Highlight tool calls
        List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
        if (!toolCalls.isEmpty()) {
            sb.append('\n')
                    .append(YELLOW)
                    .append("  ↳ Tool calls: ")
                    .append(toolCalls.size())
                    .append(RESET);
            for (ToolUseBlock tool : toolCalls) {
                sb.append('\n')
                        .append(YELLOW)
                        .append("    • ")
                        .append(tool.getName())
                        .append(RESET)
                        .append(DIM)
                        .append(" (id=")
                        .append(tool.getId())
                        .append(")")
                        .append(RESET);
                sb.append('\n')
                        .append(DIM)
                        .append("      input: ")
                        .append(formatMap(tool.getInput()))
                        .append(RESET);
            }
        }

        if (event.isStopRequested()) {
            sb.append('\n').append(RED).append("  ⚠ Stop requested").append(RESET);
        }
        log.info(sb.toString());
    }

    private void logPreActing(PreActingEvent event) {
        ToolUseBlock tool = event.getToolUse();
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append(SEPARATOR).append('\n');
        sb.append(BOLD)
                .append(YELLOW)
                .append("🔧 TOOL CALL → ")
                .append(tool.getName())
                .append(RESET);
        sb.append('\n').append(SEPARATOR);
        sb.append('\n').append(DIM).append("  id:    ").append(RESET).append(tool.getId());
        sb.append('\n')
                .append(DIM)
                .append("  input: ")
                .append(RESET)
                .append(formatMap(tool.getInput()));
        log.info(sb.toString());
    }

    private void logPostActing(PostActingEvent event) {
        ToolResultBlock result = event.getToolResult();
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append(SEPARATOR).append('\n');
        sb.append(BOLD)
                .append(GREEN)
                .append("✅ TOOL RESULT ← ")
                .append(result.getName())
                .append(RESET);
        sb.append('\n').append(SEPARATOR);
        sb.append('\n').append(DIM).append("  id:     ").append(RESET).append(result.getId());
        sb.append('\n')
                .append(DIM)
                .append("  output: ")
                .append(RESET)
                .append(extractToolOutputText(result, "(empty)"));

        if (result.isSuspended()) {
            sb.append('\n')
                    .append(MAGENTA)
                    .append("  ⏸ Suspended — waiting for user response")
                    .append(RESET);
        }
        log.info(sb.toString());
    }

    private void logPostCall(PostCallEvent event) {
        Msg msg = event.getFinalMessage();
        if (msg == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append('\n').append(SEPARATOR).append('\n');
        sb.append(BOLD).append(GREEN).append("◀ AGENT RESULT").append(RESET);
        if (msg.getGenerateReason() != null) {
            sb.append(DIM).append("  reason=").append(msg.getGenerateReason()).append(RESET);
        }
        sb.append('\n').append(SEPARATOR);
        sb.append('\n').append(formatMsg(msg));
        log.info(sb.toString());
    }

    private void logError(ErrorEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append(SEPARATOR).append('\n');
        sb.append(BOLD).append(RED).append("❌ ERROR").append(RESET);
        sb.append('\n').append(SEPARATOR);
        sb.append('\n')
                .append(RED)
                .append("  ")
                .append(event.getError().getClass().getSimpleName())
                .append(": ")
                .append(event.getError().getMessage())
                .append(RESET);
        log.error(sb.toString());
    }

    // ==================== Formatting Helpers ====================

    private String formatMsg(Msg msg) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ")
                .append(BOLD)
                .append(roleColor(msg.getRole().name()))
                .append('[')
                .append(msg.getRole())
                .append(']')
                .append(RESET);

        if (msg.getName() != null) {
            sb.append(DIM).append(" (").append(msg.getName()).append(")").append(RESET);
        }

        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock tb) {
                String text = tb.getText();
                if (text != null && !text.isEmpty()) {
                    sb.append('\n').append("    ").append(truncate(text, 200));
                }
            } else if (block instanceof ToolUseBlock tu) {
                sb.append('\n')
                        .append(YELLOW)
                        .append("    [ToolUse] ")
                        .append(tu.getName())
                        .append(RESET)
                        .append(DIM)
                        .append(" → ")
                        .append(truncate(formatMap(tu.getInput()), 150))
                        .append(RESET);
            } else if (block instanceof ToolResultBlock tr) {
                sb.append('\n')
                        .append(GREEN)
                        .append("    [ToolResult] ")
                        .append(tr.getName())
                        .append(RESET)
                        .append(DIM)
                        .append(" → ")
                        .append(truncate(extractToolOutputText(tr, "(empty)"), 150))
                        .append(RESET);
            } else {
                sb.append('\n')
                        .append(DIM)
                        .append("    [")
                        .append(block.getClass().getSimpleName())
                        .append("]")
                        .append(RESET);
            }
        }
        return sb.toString();
    }

    private String roleColor(String role) {
        return switch (role) {
            case "USER" -> CYAN;
            case "ASSISTANT" -> GREEN;
            case "SYSTEM" -> MAGENTA;
            case "TOOL" -> YELLOW;
            default -> "";
        };
    }

    static String extractToolOutputText(ToolResultBlock result, String fallback) {
        List<ContentBlock> outputs = result.getOutput();
        if (outputs == null || outputs.isEmpty()) return fallback;
        String text =
                outputs.stream()
                        .filter(TextBlock.class::isInstance)
                        .map(b -> ((TextBlock) b).getText())
                        .collect(Collectors.joining());
        return text.isEmpty() ? fallback : text;
    }

    private String formatMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringJoiner sj = new StringJoiner(", ", "{", "}");
        map.forEach((k, v) -> sj.add(k + "=" + v));
        return sj.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        String oneLine = text.replace('\n', ' ').replace('\r', ' ');
        if (oneLine.length() <= maxLen) return oneLine;
        return oneLine.substring(0, maxLen) + "...";
    }
}
