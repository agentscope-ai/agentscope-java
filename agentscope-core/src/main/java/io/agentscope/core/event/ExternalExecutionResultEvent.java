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
import io.agentscope.core.message.ToolResultBlock;
import java.util.List;

/**
 * Emitted after externally-executed tool results are provided back to the agent.
 */
public class ExternalExecutionResultEvent extends AgentEvent {

    private final String replyId;
    private final List<ToolResultBlock> toolResults;

    @JsonCreator
    public ExternalExecutionResultEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("toolResults") List<ToolResultBlock> toolResults) {
        super(id, createdAt);
        this.replyId = replyId;
        this.toolResults = toolResults != null ? List.copyOf(toolResults) : List.of();
    }

    public ExternalExecutionResultEvent(String replyId, List<ToolResultBlock> toolResults) {
        this.replyId = replyId;
        this.toolResults = toolResults != null ? List.copyOf(toolResults) : List.of();
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.EXTERNAL_EXECUTION_RESULT;
    }

    public String getReplyId() {
        return replyId;
    }

    public List<ToolResultBlock> getToolResults() {
        return toolResults;
    }
}
