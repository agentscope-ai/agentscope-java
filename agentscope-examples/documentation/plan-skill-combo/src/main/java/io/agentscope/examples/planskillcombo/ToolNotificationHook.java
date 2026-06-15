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
package io.agentscope.examples.planskillcombo;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * {@link Hook} that publishes tool-call and tool-result events as {@link ChatResp} to {@link
 * ToolEventBus}, mirroring {@code io.agentscope.builder.web.toolbus.ToolNotificationHook}.
 *
 * <p>In standalone mode, the session key is passed via constructor.
 */
public class ToolNotificationHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ToolNotificationHook.class);

    private final ToolEventBus bus;
    private final String sessionKey;
    private final boolean captureThinking;

    private static final Set<String> PLAN_RELATED_TOOLS =
            Set.of(
                    "create_plan",
                    "update_plan_info",
                    "revise_current_plan",
                    "update_subtask_state",
                    "finish_subtask",
                    "view_subtasks",
                    "get_subtask_count",
                    "finish_plan",
                    "view_historical_plans",
                    "recover_historical_plan");

    public ToolNotificationHook(ToolEventBus bus, String sessionKey) {
        this(bus, sessionKey, false);
    }

    public ToolNotificationHook(ToolEventBus bus, String sessionKey, boolean captureThinking) {
        this.bus = bus;
        this.sessionKey = sessionKey;
        this.captureThinking = captureThinking;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostReasoningEvent postReasoningEvent) {
            Msg reasoningMessage = postReasoningEvent.getReasoningMessage();
//            boolean isPlanTool =
//                    reasoningMessage.getContent().stream()
//                            .filter(b -> b instanceof ToolUseBlock)
//                            .anyMatch(
//                                    b -> PLAN_RELATED_TOOLS.contains(((ToolUseBlock) b).getName()));
//            // 过滤plan tool的工具调用
//            if (isPlanTool) {
//                return Mono.just(event);
//            }

            // 捕获 thinking 内容
            if (captureThinking) {
                for (ThinkingBlock tb :
                        postReasoningEvent
                                .getReasoningMessage()
                                .getContentBlocks(ThinkingBlock.class)) {
                    if (!tb.getThinking().isEmpty()) {
                        ChatResp resp =
                                new ChatResp(
                                        postReasoningEvent.getReasoningMessage(),
                                        new ThinkingMessage(tb.getThinking()));
                        try {
                            bus.publish(sessionKey, resp);
                            log.debug("Published thinking: session={}", sessionKey);
                        } catch (Exception e) {
                            log.debug("Failed to publish thinking: {}", e.getMessage());
                        }
                    }
                }
            }

            for (ToolUseBlock tu :
                    postReasoningEvent.getReasoningMessage().getContentBlocks(ToolUseBlock.class)) {
                ChatResp resp =
                        new ChatResp(
                                postReasoningEvent.getReasoningMessage(),
                                new ToolCallMessage(tu.getId(), tu.getName(), tu.getInput()));
                try {
                    bus.publish(sessionKey, resp);
                    log.debug("Published toolCall: session={}, tool={}", sessionKey, tu.getName());
                } catch (Exception e) {
                    log.debug(
                            "Failed to publish tool call for {}: {}", tu.getName(), e.getMessage());
                }
            }
        } else if (event instanceof PostActingEvent act) {
            ToolResultBlock tr = act.getToolResult();
//            boolean isPlanTool = PLAN_RELATED_TOOLS.contains(act.getToolResult().getName());
//            // 过滤plan tool的工具结果
//            if (isPlanTool) {
//                return Mono.just(event);
//            }
            StringBuilder resultText = new StringBuilder();
            if (tr.getOutput() != null) {
                for (ContentBlock output : tr.getOutput()) {
                    if (output instanceof TextBlock tb
                            && tb.getText() != null
                            && !tb.getText().isEmpty()) {
                        resultText.append(tb.getText());
                    }
                }
            }
            ChatResp resp =
                    new ChatResp(
                            act.getToolResultMsg(),
                            new ToolResultMessage(
                                    tr.getId(), act.getToolUse().getName(), resultText.toString()));
            try {
                bus.publish(sessionKey, resp);
                log.debug(
                        "Published toolResult: session={}, tool={}",
                        sessionKey,
                        act.getToolUse().getName());
            } catch (Exception e) {
                log.debug(
                        "Failed to publish tool result for {}: {}",
                        act.getToolUse().getName(),
                        e.getMessage());
            }
        }
        return Mono.just(event);
    }
}
