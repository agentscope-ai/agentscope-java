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
package io.agentscope.core.agui.adapter.strategy;

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agui.adapter.StreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.message.ToolUseBlock;
import java.util.UUID;

/**
 * Converter for handling ToolUseBlock events, transforming them into AG-UI ToolCallStart and ToolCallArgs events.
 */
public class ToolUseBlockConverter implements BlockEventConverter<ToolUseBlock> {

    @Override
    public boolean isApplicable(Event event) {
        return event.getType() == EventType.REASONING || event.getType() == EventType.SUMMARY;
    }

    @Override
    public void convert(ToolUseBlock block, Event event, StreamContext ctx) {
        String toolCallId =
                block.getId() != null && !block.getId().isBlank()
                        ? block.getId()
                        : UUID.randomUUID().toString();

        if (!ctx.isToolActive(toolCallId)) {
            // End any active Text/Reasoning message before starting tool call
            ctx.flushAllActiveTexts();
            ctx.flushAllActiveReasonings();

            String toolName =
                    block.getName() != null && !block.getName().isBlank()
                            ? block.getName()
                            : "unknown";

            ctx.emit(
                    new AguiEvent.ToolCallStart(
                            ctx.getThreadId(), ctx.getRunId(), toolCallId, toolName));
            ctx.deferEndEvent(
                    StreamContext.PREFIX_TOOL + toolCallId,
                    new AguiEvent.ToolCallEnd(ctx.getThreadId(), ctx.getRunId(), toolCallId));
            ctx.addActiveTool(toolCallId);
        }

        // Emit tool call args if enabled
        if (ctx.getConfig().isEmitToolCallArgs() && !event.isLast()) {
            String args = block.getContent();
            if (args != null && !args.isEmpty()) {
                ctx.emit(
                        new AguiEvent.ToolCallArgs(
                                ctx.getThreadId(), ctx.getRunId(), toolCallId, args));
            }
        }
    }
}
