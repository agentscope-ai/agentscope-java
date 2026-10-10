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
import io.agentscope.core.message.TextBlock;
import java.util.Map;

/**
 * An intermediate update published by a running tool through {@link
 * io.agentscope.core.tool.ToolEmitter}.
 *
 * <p>{@link ToolResultTextDeltaEvent} and {@link ToolResultDataDeltaEvent} carry the tool's result,
 * which is also what the model receives. Progress carries no such promise: {@code ToolEmitter}'s
 * contract says emitted chunks are not sent to the LLM, so a consumer that rebuilt a tool result
 * from the delta stream would persist progress as the return value. Keeping the two on separate
 * event types makes that confusion impossible rather than merely discouraged.
 */
public class ToolProgressEvent extends AgentEvent {

    private final String replyId;
    private final String toolCallId;
    private final String toolCallName;
    private final ContentBlock content;

    @JsonCreator
    public ToolProgressEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("toolCallId") String toolCallId,
            @JsonProperty("toolCallName") String toolCallName,
            @JsonProperty("content") ContentBlock content,
            @JsonProperty("metadata") Map<String, Object> metadata) {
        super(id, createdAt);
        this.replyId = replyId;
        this.toolCallId = toolCallId;
        this.toolCallName = toolCallName;
        this.content = content;
        this.withMetadata(metadata);
    }

    /**
     * Backward-compatible constructor for callers that do not provide metadata.
     */
    public ToolProgressEvent(
            String id,
            String createdAt,
            String replyId,
            String toolCallId,
            String toolCallName,
            ContentBlock content) {
        this(id, createdAt, replyId, toolCallId, toolCallName, content, null);
    }

    public ToolProgressEvent(
            String replyId, String toolCallId, String toolCallName, ContentBlock content) {
        this.replyId = replyId;
        this.toolCallId = toolCallId;
        this.toolCallName = toolCallName;
        this.content = content;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.TOOL_PROGRESS;
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

    /**
     * The progress chunk as published by the tool.
     *
     * @return the emitted content block, text or otherwise
     */
    public ContentBlock getContent() {
        return content;
    }

    /**
     * The progress chunk when it is text.
     *
     * <p>Most consumers only render progress, so the common case is exposed directly; a non-text
     * chunk reads as {@code null} here and stays available through {@link #getContent()}.
     *
     * @return the chunk's text, or {@code null} when the chunk is not a text block
     */
    public String getText() {
        return content instanceof TextBlock textBlock ? textBlock.getText() : null;
    }
}
