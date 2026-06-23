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

public class ToolResultStartEvent extends AgentEvent {

    private final String replyId;
    private final String toolCallId;
    private final String toolCallName;
    private final String toolCallTitle;

    @JsonCreator
    public ToolResultStartEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("toolCallId") String toolCallId,
            @JsonProperty("toolCallName") String toolCallName,
            @JsonProperty("toolCallTitle") String toolCallTitle) {
        super(id, createdAt);
        this.replyId = replyId;
        this.toolCallId = toolCallId;
        this.toolCallName = toolCallName;
        this.toolCallTitle = toolCallTitle;
    }

    public ToolResultStartEvent(
            String replyId, String toolCallId, String toolCallName, String toolCallTitle) {
        this.replyId = replyId;
        this.toolCallId = toolCallId;
        this.toolCallName = toolCallName;
        this.toolCallTitle = toolCallTitle;
    }

    public ToolResultStartEvent(String replyId, String toolCallId, String toolCallName) {
        this(replyId, toolCallId, toolCallName, null);
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.TOOL_RESULT_START;
    }

    public String getReplyId() {
        return replyId;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getToolCallName() {
        return toolCallName;
    }

    public String getToolCallTitle() {
        return toolCallTitle;
    }
}
