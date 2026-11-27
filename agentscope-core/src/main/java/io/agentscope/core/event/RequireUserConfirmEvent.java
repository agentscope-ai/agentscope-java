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
package io.agentscope.core.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;

/**
 * Emitted when the agent requires user confirmation before executing tool calls (HITL).
 *
 * <p>{@code confirmationMessages} carries the human-readable message each tool's permission
 * check attached to its ASK decision ({@code PermissionDecision.ask(message)}), keyed by tool
 * call id, so callers and frontends can show why the confirmation is needed instead of an
 * opaque tool list. Absent (empty) when no tool supplied a message; older serialized payloads
 * without the field deserialize to an empty map (#3315).
 */
public class RequireUserConfirmEvent extends AgentEvent {

    private final String replyId;
    private final List<ToolUseBlock> toolCalls;
    private final Map<String, String> confirmationMessages;

    @JsonCreator
    public RequireUserConfirmEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("toolCalls") List<ToolUseBlock> toolCalls,
            @JsonProperty("confirmationMessages") Map<String, String> confirmationMessages) {
        super(id, createdAt);
        this.replyId = replyId;
        this.toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
        this.confirmationMessages =
                confirmationMessages != null ? Map.copyOf(confirmationMessages) : Map.of();
    }

    public RequireUserConfirmEvent(
            String replyId,
            List<ToolUseBlock> toolCalls,
            Map<String, String> confirmationMessages) {
        this.replyId = replyId;
        this.toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
        this.confirmationMessages =
                confirmationMessages != null ? Map.copyOf(confirmationMessages) : Map.of();
    }

    public RequireUserConfirmEvent(String replyId, List<ToolUseBlock> toolCalls) {
        this(replyId, toolCalls, Map.of());
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.REQUIRE_USER_CONFIRM;
    }

    public String getReplyId() {
        return replyId;
    }

    public List<ToolUseBlock> getToolCalls() {
        return toolCalls;
    }

    /**
     * Returns the per-call ASK messages keyed by tool call id; empty when no tool supplied one.
     */
    public Map<String, String> getConfirmationMessages() {
        return confirmationMessages;
    }
}
