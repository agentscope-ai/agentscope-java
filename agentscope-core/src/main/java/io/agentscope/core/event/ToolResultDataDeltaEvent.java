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
import io.agentscope.core.message.ContentBlock;
import java.util.Map;

public class ToolResultDataDeltaEvent extends AgentEvent {

    private final String replyId;
    private final String toolCallId;
    private final String toolCallName;
    private final ContentBlock data;

    @JsonCreator
    public ToolResultDataDeltaEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("toolCallId") String toolCallId,
            @JsonProperty("toolCallName") String toolCallName,
            @JsonProperty("data") ContentBlock data,
            @JsonProperty("metadata") Map<String, Object> metadata) {
        super(id, createdAt);
        this.replyId = replyId;
        this.toolCallId = toolCallId;
        this.toolCallName = toolCallName;
        this.data = data;
        this.withMetadata(metadata);
    }

    /**
     * Backward-compatible constructor for callers that do not provide metadata.
     */
    public ToolResultDataDeltaEvent(
            String id,
            String createdAt,
            String replyId,
            String toolCallId,
            String toolCallName,
            ContentBlock data) {
        this(id, createdAt, replyId, toolCallId, toolCallName, data, null);
    }

    public ToolResultDataDeltaEvent(
            String replyId, String toolCallId, String toolCallName, ContentBlock data) {
        this.replyId = replyId;
        this.toolCallId = toolCallId;
        this.toolCallName = toolCallName;
        this.data = data;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.TOOL_RESULT_DATA_DELTA;
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

    public ContentBlock getData() {
        return data;
    }
}
