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
package io.agentscope.examples.agui.config;

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agui.adapter.StreamContext;
import io.agentscope.core.agui.adapter.strategy.ToolResultBlockConverter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Custom converter that overrides the framework's default ToolResultBlockConverter
 * to emit ag-ui tool progress events.
 *
 * <p>Before overriding any default BlockEventConverter, please ensure you have a
 * thorough understanding of the default implementation.
 *
 * <p>IMPORTANT: In production environments, please modify with caution other than tool
 * progress events. Otherwise, you assume all consequences and risks.
 *
 * <p>This is currently provided as a demonstration version only. Please modify it
 * according to your own requirements.
 *
 * <p>Feel free to submit an issue on GitHub if you encounter any problems.
 */
@Component
public class CustomToolResultBlockConverter extends ToolResultBlockConverter {

    @Override
    public boolean isApplicable(Event event) {
        // return event.getType() == EventType.TOOL_RESULT && event.isLast();
        // The check for event.isLast() needs to be removed, otherwise only the final result event
        // will be emitted
        return event.getType() == EventType.TOOL_RESULT;
    }

    @Override
    public void convert(ToolResultBlock block, Event event, StreamContext ctx) {
        // Extract the tool invocation progress from metadata and package it into a ToolCallResult
        // event
        // This is currently provided as a demonstration version only.
        // Please modify it according to your own requirements.
        if (block.getMetadata() != null && block.getMetadata().size() != 0) {
            ctx.emit(
                    new AguiEvent.ToolCallResult(
                            ctx.getThreadId(),
                            ctx.getRunId(),
                            // TODO: Currently using tool name to replace tool call id
                            block.getName(),
                            JsonUtils.getJsonCodec().toJson(block.getMetadata()),
                            "tool",
                            event.getMessage().getId()));
            return;
        }

        String toolCallId =
                block.getId() != null && !block.getId().isBlank()
                        ? block.getId()
                        : UUID.randomUUID().toString();

        String result = super.extractToolResultText(block);

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
}
