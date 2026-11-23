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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ExternalExecutionForm;
import java.util.List;
import java.util.Map;

/**
 * Emitted when tool calls require external (out-of-process) execution.
 */
public class RequireExternalExecutionEvent extends AgentEvent {

    private final String replyId;
    private final List<ToolUseBlock> toolCalls;
    private final Map<String, ExternalExecutionForm> inputForms;

    @JsonCreator
    public RequireExternalExecutionEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("toolCalls") List<ToolUseBlock> toolCalls,
            @JsonProperty("inputForms") Map<String, ExternalExecutionForm> inputForms) {
        super(id, createdAt);
        this.replyId = replyId;
        this.toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
        this.inputForms = inputForms != null ? Map.copyOf(inputForms) : Map.of();
    }

    public RequireExternalExecutionEvent(
            String id, String createdAt, String replyId, List<ToolUseBlock> toolCalls) {
        this(id, createdAt, replyId, toolCalls, null);
    }

    public RequireExternalExecutionEvent(String replyId, List<ToolUseBlock> toolCalls) {
        this(replyId, toolCalls, null);
    }

    public RequireExternalExecutionEvent(
            String replyId,
            List<ToolUseBlock> toolCalls,
            Map<String, ExternalExecutionForm> inputForms) {
        this.replyId = replyId;
        this.toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
        this.inputForms = inputForms != null ? Map.copyOf(inputForms) : Map.of();
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.REQUIRE_EXTERNAL_EXECUTION;
    }

    public String getReplyId() {
        return replyId;
    }

    public List<ToolUseBlock> getToolCalls() {
        return toolCalls;
    }

    /**
     * Gets the optional input forms by tool call ID.
     *
     * @return form descriptors for the calls that supplied one
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, ExternalExecutionForm> getInputForms() {
        return inputForms;
    }
}
