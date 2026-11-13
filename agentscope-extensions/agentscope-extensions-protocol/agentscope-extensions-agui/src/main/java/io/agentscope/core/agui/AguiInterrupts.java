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
package io.agentscope.core.agui;

import static io.agentscope.core.agui.AguiInterruptConstants.INTERRUPT_KIND_PERMISSION_CONFIRM;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_AGENTSCOPE_INTERRUPT_KIND;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_REPLY_ID;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_TOOL_CONTENT;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_TOOL_INPUT;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_TOOL_NAME;
import static io.agentscope.core.agui.AguiInterruptConstants.TOOL_CALL_INTERRUPT_REASON;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared factory for AG-UI tool-bound interrupts.
 *
 * <p>Live {@code AgentEvent} conversion and SessionLog projection must produce the same interrupt
 * shape, because the resume path rebuilds tool calls from the interrupt metadata. Keeping the
 * builders here avoids drift between the two paths.
 */
public final class AguiInterrupts {

    /** Default message for an externally executed tool that is waiting for its result. */
    public static final String DEFAULT_EXTERNAL_EXECUTION_MESSAGE = "Awaiting external execution";

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
                                    "Full replacement of the tool args. Not merged."),
                            "reason",
                            Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "Optional explanation supplied when the tool call is denied.")),
                    "required",
                    List.of("approved"));

    private AguiInterrupts() {}

    /**
     * Builds a permission-mode confirmation interrupt for one tool call.
     *
     * @param replyId originating reply id, may be {@code null} when unknown
     * @param toolUse pending tool call
     * @return the AG-UI interrupt
     */
    public static AguiEvent.Interrupt permissionConfirm(String replyId, ToolUseBlock toolUse) {
        String toolCallId = toolUse.getId();
        Map<String, Object> metadata = toolMetadata(toolUse);
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

    /**
     * Builds an external-execution interrupt for one suspended tool call.
     *
     * @param replyId originating reply id, may be {@code null} when unknown
     * @param toolUse suspended tool call
     * @param message suspension message, or {@code null} for the default
     * @return the AG-UI interrupt
     */
    public static AguiEvent.Interrupt externalExecution(
            String replyId, ToolUseBlock toolUse, String message) {
        String toolCallId = toolUse.getId();
        Map<String, Object> metadata = toolMetadata(toolUse);
        if (!isBlank(replyId)) {
            metadata.put(METADATA_REPLY_ID, replyId);
        }
        return new AguiEvent.Interrupt(
                interruptId(replyId, toolCallId),
                TOOL_CALL_INTERRUPT_REASON,
                message != null ? message : DEFAULT_EXTERNAL_EXECUTION_MESSAGE,
                toolCallId,
                null,
                null,
                Map.copyOf(metadata));
    }

    /**
     * Interrupt identity: {@code replyId:toolCallId} when the reply is known, otherwise the tool
     * call id alone.
     *
     * @param replyId originating reply id, may be {@code null}
     * @param toolCallId tool call id
     * @return stable interrupt id
     */
    public static String interruptId(String replyId, String toolCallId) {
        if (!isBlank(replyId)) {
            return replyId + ":" + toolCallId;
        }
        return toolCallId;
    }

    private static Map<String, Object> toolMetadata(ToolUseBlock toolUse) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (!isBlank(toolUse.getName())) {
            metadata.put(METADATA_TOOL_NAME, toolUse.getName());
        }
        if (toolUse.getInput() != null && !toolUse.getInput().isEmpty()) {
            metadata.put(METADATA_TOOL_INPUT, toolUse.getInput());
        }
        metadata.put(METADATA_TOOL_CONTENT, JsonUtils.resolveToolCallArgsJson(toolUse));
        return metadata;
    }

    private static String confirmMessage(ToolUseBlock toolUse) {
        String name = isBlank(toolUse.getName()) ? "tool" : toolUse.getName();
        return "Tool '" + name + "' requires user confirmation before execution";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
