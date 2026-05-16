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
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.UUID;

/**
 * Converter for handling ToolResultBlock events, transforming them into AG-UI ToolCallResult events.
 */
public class ToolResultBlockConverter implements BlockEventConverter<ToolResultBlock> {

    @Override
    public boolean isApplicable(Event event) {
        return event.getType() == EventType.TOOL_RESULT && event.isLast();
    }

    @Override
    public void convert(ToolResultBlock block, Event event, StreamContext ctx) {
        String toolCallId =
                block.getId() != null
                        ? block.getId()
                        : (ctx.getLastActiveToolId() != null
                                ? ctx.getLastActiveToolId()
                                : UUID.randomUUID().toString());
        String result = extractToolResultText(block);

        // Closing Start/End Phase
        if (ctx.isToolActive(toolCallId)) {
            ctx.flushEndEvent(StreamContext.PREFIX_TOOL + toolCallId);
        } else {
            // Fall-back: The previous process did not proceed to Start for some reason
            // (e.g., recovery directly from the context)
            String toolName =
                    block.getName() != null && !block.getName().isBlank()
                            ? block.getName()
                            : "unknown";
            ctx.emit(
                    new AguiEvent.ToolCallStart(
                            ctx.getThreadId(), ctx.getRunId(), toolCallId, toolName));
            ctx.emit(new AguiEvent.ToolCallEnd(ctx.getThreadId(), ctx.getRunId(), toolCallId));
        }

        ctx.emit(
                new AguiEvent.ToolCallResult(
                        ctx.getThreadId(),
                        ctx.getRunId(),
                        toolCallId,
                        result,
                        "tool",
                        event.getMessage().getId()));

        if (ctx.isToolActive(toolCallId)) {
            ctx.removeActiveTool(toolCallId);
        }
    }

    /**
     * Extract text content from a tool result block.
     *
     * @param toolResult The tool result block
     * @return The text content, or null if not present
     */
    private String extractToolResultText(ToolResultBlock toolResult) {
        if (toolResult.getOutput() == null || toolResult.getOutput().isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (ContentBlock output : toolResult.getOutput()) {
            if (output instanceof TextBlock textBlock) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(textBlock.getText());
            }
        }

        return !sb.isEmpty() ? sb.toString() : null;
    }
}
