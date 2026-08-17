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

/**
 * Emitted when the agent requires user confirmation before executing tool calls (HITL).
 */
public class RequireUserConfirmEvent extends AgentEvent {

    private final String replyId;
    private final List<ToolUseBlock> toolCalls;

    @JsonCreator
    public RequireUserConfirmEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("toolCalls") List<ToolUseBlock> toolCalls) {
        super(id, createdAt);
        this.replyId = replyId;
        this.toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
    }

    public RequireUserConfirmEvent(String replyId, List<ToolUseBlock> toolCalls) {
        this.replyId = replyId;
        this.toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
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
}
