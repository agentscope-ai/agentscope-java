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
package io.agentscope.builder.web.toolbus;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.legacy.hook.Hook;
import io.agentscope.core.legacy.hook.HookEvent;
import io.agentscope.core.legacy.hook.PostReasoningEvent;
import io.agentscope.core.legacy.hook.RuntimeContextAware;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * {@link Hook} + {@link RuntimeContextAware} implementation that publishes tool-call events to
 * {@link ToolEventBus} before each tool execution, enabling real-time SSE streaming of tool calls
 * in {@link io.agentscope.builder.web.api.ChatController}.
 *
 * <p>The session key is derived from {@link RuntimeContext#getSessionKey()} which is set per call
 * by the agent runtime.
 */
public class ToolNotificationHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(ToolNotificationHook.class);

    private final ToolEventBus bus;

    private RuntimeContext runtimeContext;

    public ToolNotificationHook(ToolEventBus bus) {
        this.bus = bus;
    }

    @Override
    public void setRuntimeContext(RuntimeContext ctx) {
        this.runtimeContext = ctx;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PostReasoningEvent pre)) {
            return Mono.just(event);
        }
        Msg msg = pre.getReasoningMessage();
        if (msg == null) return Mono.just(event);

        List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
        if (toolCalls == null || toolCalls.isEmpty()) return Mono.just(event);

        String sessionKey = resolveSessionKey();
        if (sessionKey == null) return Mono.just(event);

        for (ToolUseBlock tu : toolCalls) {
            Map<String, Object> inputData = new LinkedHashMap<>();
            if (tu.getInput() != null) {
                inputData.putAll(tu.getInput());
            }
            try {
                bus.publish(ToolEventBus.ToolEvent.toolCall(sessionKey, tu.getName(), inputData));
                log.debug(
                        "Published TOOL_CALL event: session={}, tool={}", sessionKey, tu.getName());
            } catch (Exception e) {
                log.debug("Failed to publish tool event for {}: {}", tu.getName(), e.getMessage());
            }
        }
        return Mono.just(event);
    }

    private String resolveSessionKey() {
        RuntimeContext ctx = this.runtimeContext;
        if (ctx == null) return null;
        if (ctx.getSessionKey() != null) {
            return ctx.getSessionKey().toIdentifier();
        }
        // Fallback: use userId as discriminator if no session key
        return ctx.getUserId();
    }
}
