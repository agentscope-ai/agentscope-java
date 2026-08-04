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
import io.agentscope.core.agui.model.AguiTool;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Converts AgentScope lifecycle events to AG-UI run lifecycle events.
 *
 * <p>Agent start and end events are the authoritative source for normal AG-UI run lifecycle
 * messages. Interrupts are emitted for:
 *
 * <ul>
 *   <li>Permission HITL ({@link RequireUserConfirmEvent})
 *   <li>Backend SchemaOnly / {@code ToolSuspendException} pauses that are <em>not</em> frontend
 *       tools from {@link RunAgentInput#getTools()}
 * </ul>
 *
 * <p>Frontend SchemaOnly tools stay on the standard {@code TOOL_CALL_*} path and finish with a
 * plain {@code RUN_FINISHED}; the client returns results as tool messages.
 */
final class AgentLifecycleEventConverter implements AgentEventConverter {

    static final String INTERRUPT_REASON_TOOL_CALL = "tool_call";
    static final String METADATA_SOURCE = "source";
    static final String SOURCE_PERMISSION = "permission";
    static final String SOURCE_TOOL_SUSPENDED = "tool_suspended";

    private static final Map<String, Object> TOOL_CALL_APPROVAL_SCHEMA =
            Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                            "approved",
                            Map.of("type", "boolean"),
                            "reason",
                            Map.of("type", "string", "description", "reason for rejection")),
                    "required",
                    List.of("approved"));

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(
                AgentStartEvent.class,
                AgentEndEvent.class,
                AgentResultEvent.class,
                RequireUserConfirmEvent.class);
    }

    /**
     * Convert AgentScope lifecycle events into AG-UI run started, run finished, or interrupt state.
     *
     * @param event source lifecycle event
     * @param context stream conversion context
     */
    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        if (event instanceof AgentStartEvent) {
            context.emit(
                    new AguiEvent.RunStarted(
                            context.getThreadId(),
                            context.getRunId(),
                            null,
                            context.getRunInput()));
        } else if (event instanceof RequireUserConfirmEvent confirmEvent) {
            collectPermissionInterrupts(confirmEvent, context);
        } else if (event instanceof AgentResultEvent resultEvent) {
            collectBackendSuspendedToolInterrupts(resultEvent, context);
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

    private static void collectPermissionInterrupts(
            RequireUserConfirmEvent confirmEvent, AguiStreamContext context) {
        for (ToolUseBlock toolUse : confirmEvent.getToolCalls()) {
            if (toolUse == null || isBlank(toolUse.getId())) {
                continue;
            }
            context.addInterrupt(buildPermissionInterrupt(confirmEvent.getReplyId(), toolUse));
        }
    }

    private static void collectBackendSuspendedToolInterrupts(
            AgentResultEvent resultEvent, AguiStreamContext context) {
        Msg result = resultEvent.getResult();
        if (result == null || result.getGenerateReason() != GenerateReason.TOOL_SUSPENDED) {
            return;
        }

        Set<String> frontendToolNames = frontendToolNames(context.getRunInput());
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
            ToolUseBlock toolUse = toolUses.get(toolResult.getId());
            String toolName =
                    toolUse != null && !isBlank(toolUse.getName())
                            ? toolUse.getName()
                            : toolResult.getName();
            // Frontend-provided tools are executed by the client via tool messages, not resume.
            if (!isBlank(toolName) && frontendToolNames.contains(toolName)) {
                continue;
            }
            context.addInterrupt(buildSuspendedToolInterrupt(result, toolUse, toolResult));
        }
    }

    private static Set<String> frontendToolNames(RunAgentInput runInput) {
        if (runInput == null || !runInput.hasTools()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (AguiTool tool : runInput.getTools()) {
            if (tool != null && !isBlank(tool.getName())) {
                names.add(tool.getName());
            }
        }
        return names;
    }

    private static AguiEvent.Interrupt buildPermissionInterrupt(
            String replyId, ToolUseBlock toolUse) {
        Map<String, Object> metadata = baseToolMetadata(replyId, toolUse, null);
        metadata.put(METADATA_SOURCE, SOURCE_PERMISSION);

        String message =
                !isBlank(toolUse.getName())
                        ? "Approve tool call: " + toolUse.getName() + "?"
                        : "Approve tool call?";

        return new AguiEvent.Interrupt(
                interruptId(replyId, toolUse.getId()),
                INTERRUPT_REASON_TOOL_CALL,
                message,
                toolUse.getId(),
                TOOL_CALL_APPROVAL_SCHEMA,
                null,
                Map.copyOf(metadata));
    }

    private static AguiEvent.Interrupt buildSuspendedToolInterrupt(
            Msg result, ToolUseBlock toolUse, ToolResultBlock toolResult) {
        String toolCallId = toolResult.getId();
        Map<String, Object> metadata = baseToolMetadata(result.getId(), toolUse, toolResult);
        metadata.put(METADATA_SOURCE, SOURCE_TOOL_SUSPENDED);

        return new AguiEvent.Interrupt(
                interruptId(result.getId(), toolCallId),
                INTERRUPT_REASON_TOOL_CALL,
                extractText(toolResult.getOutput()),
                toolCallId,
                TOOL_CALL_APPROVAL_SCHEMA,
                null,
                Map.copyOf(metadata));
    }

    private static Map<String, Object> baseToolMetadata(
            String replyId, ToolUseBlock toolUse, ToolResultBlock toolResult) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String toolName =
                toolUse != null && !isBlank(toolUse.getName())
                        ? toolUse.getName()
                        : toolResult != null ? toolResult.getName() : null;
        if (!isBlank(toolName)) {
            metadata.put("toolName", toolName);
        }
        if (toolUse != null && toolUse.getInput() != null && !toolUse.getInput().isEmpty()) {
            metadata.put("toolInput", toolUse.getInput());
        }
        if (!isBlank(replyId)) {
            metadata.put("replyId", replyId);
        }
        return metadata;
    }

    private static String interruptId(String replyId, String toolCallId) {
        if (!isBlank(replyId)) {
            return replyId + ":" + toolCallId;
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
