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
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
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
 * is gated on {@code GenerateReason.TOOL_SUSPENDED}). Without a dedicated converter these
 * permission-confirmation events fall through to the raw fallback and are lost to standard AG-UI
 * clients.
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
 */
final class PermissionConfirmEventConverter implements AgentEventConverter {

    /** Interrupt reason used for permission-mode tool confirmations. */
    static final String CONFIRM_INTERRUPT_REASON = "tool_confirmation";

    /** Metadata key: the tool name. */
    static final String METADATA_TOOL_NAME = "toolName";

    /** Metadata key: the parsed tool arguments ({@code Map<String, Object>}). */
    static final String METADATA_TOOL_INPUT = "toolInput";

    /** Metadata key: the tool arguments serialized as a JSON-object string. */
    static final String METADATA_TOOL_CONTENT = "toolContent";

    /** Metadata key: the originating reply id. */
    static final String METADATA_REPLY_ID = "replyId";

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(RequireUserConfirmEvent.class);
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        if (!(event instanceof RequireUserConfirmEvent confirmEvent)) {
            return;
        }
        String replyId = confirmEvent.getReplyId();
        List<ToolUseBlock> toolCalls = confirmEvent.getToolCalls();
        if (toolCalls == null) {
            return;
        }
        for (ToolUseBlock toolUse : toolCalls) {
            if (toolUse == null || isBlank(toolUse.getId())) {
                continue;
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
        if (!isBlank(replyId)) {
            metadata.put(METADATA_REPLY_ID, replyId);
        }
        return new AguiEvent.Interrupt(
                interruptId(replyId, toolCallId),
                CONFIRM_INTERRUPT_REASON,
                confirmMessage(toolUse),
                toolCallId,
                null,
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
