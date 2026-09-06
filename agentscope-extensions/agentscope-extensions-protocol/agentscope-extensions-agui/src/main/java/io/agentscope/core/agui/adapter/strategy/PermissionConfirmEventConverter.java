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

import static io.agentscope.core.agui.AguiInterruptConstants.INTERRUPT_KIND_PERMISSION_CONFIRM;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_AGENTSCOPE_INTERRUPT_KIND;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_REPLY_ID;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_TOOL_CONTENT;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_TOOL_INPUT;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_TOOL_NAME;
import static io.agentscope.core.agui.AguiInterruptConstants.TOOL_CALL_INTERRUPT_REASON;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts permission-mode HITL {@link RequireUserConfirmEvent}s into AG-UI interrupt outcomes.
 *
 * <p>When an agent runs under {@code PermissionMode.DEFAULT}, the permission engine returns {@code
 * ASK} for non-readonly tools and {@code ReActAgent} emits a {@link RequireUserConfirmEvent} (paired
 * with a {@code RequestStopEvent(PERMISSION_ASKING)}) instead of executing the tool. This is a
 * distinct path from the tool-suspension flow handled by {@code AgentLifecycleEventConverter} (which
 * is gated on {@code GenerateReason.TOOL_SUSPENDED}).
 *
 * <p>Each pending {@link ToolUseBlock} is surfaced as one {@link AguiEvent.Interrupt} added to the
 * stream context; {@code AgentLifecycleEventConverter} drains them into the {@code RUN_FINISHED}
 * interrupt outcome on {@code AgentEndEvent}. The interrupt id reuses the {@code replyId:toolCallId}
 * format so the resume path can recover the reply/tool-call identity.
 *
 * <p>The interrupt metadata carries {@code toolContent} — a valid JSON-object string of the tool
 * arguments — so the resume path can rebuild a {@link ToolUseBlock} whose {@code content} is
 * non-null. This matters because {@code ReActAgent.applyConfirmResults} fully replaces the stored
 * {@code ToolUseBlock}, and tool-input validation reads {@code content} directly with no fallback to
 * {@code input}; a null content would fail the resume with {@code argument "content" is null}.
 *
 * <p>{@link UserConfirmResultEvent} is intentionally registered here as a no-op.
 */
final class PermissionConfirmEventConverter implements AgentEventConverter {

    private static final Map<String, Object> CONFIRM_RESPONSE_SCHEMA =
            Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                            "approved",
                            Map.of("type", "boolean"),
                            "editedArgs",
                            Map.of(
                                    "type",
                                    "object",
                                    "description",
                                    "Full replacement of the tool args. Not merged.")),
                    "required",
                    List.of("approved"));

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(RequireUserConfirmEvent.class, UserConfirmResultEvent.class);
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        if (event instanceof UserConfirmResultEvent) {
            return;
        }
        RequireUserConfirmEvent confirmEvent = (RequireUserConfirmEvent) event;
        String replyId = confirmEvent.getReplyId();

        List<ToolUseBlock> toolCalls = confirmEvent.getToolCalls();
        for (ToolUseBlock toolUse : toolCalls) {
            // toolUse will not be null, see RequireUserConfirmEvent constructor
            if (isBlank(toolUse.getId())) {
                throw new IllegalStateException(
                        "RequireUserConfirmEvent contains a tool call without a stable id");
            }
            context.addInterrupt(buildInterrupt(replyId, toolUse));
        }
    }

    private static AguiEvent.Interrupt buildInterrupt(String replyId, ToolUseBlock toolUse) {
        String toolCallId = toolUse.getId();
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (!isBlank(toolUse.getName())) {
            metadata.put(METADATA_TOOL_NAME, toolUse.getName());
        }
        if (toolUse.getInput() != null && !toolUse.getInput().isEmpty()) {
            metadata.put(METADATA_TOOL_INPUT, toolUse.getInput());
        }
        metadata.put(METADATA_TOOL_CONTENT, JsonUtils.resolveToolCallArgsJson(toolUse));
        metadata.put(METADATA_AGENTSCOPE_INTERRUPT_KIND, INTERRUPT_KIND_PERMISSION_CONFIRM);
        if (!isBlank(replyId)) {
            metadata.put(METADATA_REPLY_ID, replyId);
        }
        return new AguiEvent.Interrupt(
                interruptId(replyId, toolCallId),
                TOOL_CALL_INTERRUPT_REASON,
                confirmMessage(toolUse),
                toolCallId,
                CONFIRM_RESPONSE_SCHEMA,
                null,
                Map.copyOf(metadata));
    }

    private static String confirmMessage(ToolUseBlock toolUse) {
        String name = isBlank(toolUse.getName()) ? "tool" : toolUse.getName();
        return "Tool '" + name + "' requires user confirmation before execution";
    }

    private static String interruptId(String replyId, String toolCallId) {
        if (!isBlank(replyId)) {
            return replyId + ":" + toolCallId;
        }
        return toolCallId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
