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

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class AgentLifecycleEventConverter implements AgentEventConverter {

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(
                AgentStartEvent.class,
                AgentEndEvent.class,
                AgentResultEvent.class,
                ModelCallStartEvent.class);
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        if (event instanceof AgentStartEvent) {
            context.emit(
                    new AguiEvent.RunStarted(
                            context.getThreadId(),
                            context.getRunId(),
                            null,
                            context.getRunInput()));
        } else if (event instanceof AgentResultEvent resultEvent) {
            collectSuspendedToolInterrupts(resultEvent, context);
        } else if (event instanceof AgentEndEvent) {
            for (AguiEvent pendingEvent : context.finishPendingEvents()) {
                context.emit(pendingEvent);
            }
            List<AguiEvent.Interrupt> interrupts = context.getPendingInterrupts();
            AguiEvent.RunFinishedOutcome outcome =
                    interrupts.isEmpty()
                            ? null
                            : new AguiEvent.RunFinishedInterruptOutcome(interrupts);
            context.emit(
                    new AguiEvent.RunFinished(
                            context.getThreadId(), context.getRunId(), null, outcome));
        }
    }

    private static void collectSuspendedToolInterrupts(
            AgentResultEvent resultEvent, AguiStreamContext context) {
        Msg result = resultEvent.getResult();
        if (result == null || result.getGenerateReason() != GenerateReason.TOOL_SUSPENDED) {
            return;
        }

        Map<String, ToolUseBlock> toolUses = new LinkedHashMap<>();
        for (ContentBlock block : result.getContent()) {
            if (block instanceof ToolUseBlock toolUse && !isBlank(toolUse.getId())) {
                toolUses.put(toolUse.getId(), toolUse);
            }
        }

        for (ContentBlock block : result.getContent()) {
            if (!(block instanceof ToolResultBlock toolResult)
                    || !toolResult.isSuspended()
                    || isBlank(toolResult.getId())) {
                continue;
            }
            context.addInterrupt(
                    buildToolCallInterrupt(result, toolUses.get(toolResult.getId()), toolResult));
        }
    }

    private static AguiEvent.Interrupt buildToolCallInterrupt(
            Msg result, ToolUseBlock toolUse, ToolResultBlock toolResult) {
        String toolCallId = toolResult.getId();
        Map<String, Object> metadata = new LinkedHashMap<>();
        String toolName =
                toolUse != null && !isBlank(toolUse.getName())
                        ? toolUse.getName()
                        : toolResult.getName();
        if (!isBlank(toolName)) {
            metadata.put("toolName", toolName);
        }
        if (toolUse != null && toolUse.getInput() != null && !toolUse.getInput().isEmpty()) {
            metadata.put("toolInput", toolUse.getInput());
        }
        if (!isBlank(result.getId())) {
            metadata.put("replyId", result.getId());
        }

        return new AguiEvent.Interrupt(
                interruptId(result, toolCallId),
                "tool_call",
                extractText(toolResult.getOutput()),
                toolCallId,
                null,
                null,
                metadata.isEmpty() ? null : Map.copyOf(metadata));
    }

    private static String interruptId(Msg result, String toolCallId) {
        if (!isBlank(result.getId())) {
            return result.getId() + ":" + toolCallId;
        }
        return toolCallId;
    }

    private static String extractText(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        String text =
                blocks.stream()
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::getText)
                        .filter(value -> value != null && !value.isEmpty())
                        .collect(Collectors.joining("\n"));
        return text.isEmpty() ? null : text;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
