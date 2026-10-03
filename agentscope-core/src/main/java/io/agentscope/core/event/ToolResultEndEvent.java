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
import io.agentscope.core.message.ToolResultState;
import java.util.Map;

public class ToolResultEndEvent extends AgentEvent {

    private final String replyId;
    private final String toolCallId;
    private final String toolCallName;
    private final ToolResultState state;

    /**
     * The tool method's return value as text, or {@code null} when the producer did not report one.
     *
     * <p>A tool that streams progress through {@link io.agentscope.core.tool.ToolEmitter} pushes
     * those chunks onto the event stream as deltas, but per that emitter's contract progress is not
     * what the model receives — only the return value is. A consumer rebuilding the tool message
     * from the delta stream alone would persist progress text as the result, so the authoritative
     * value travels here. It deliberately does not ride on {@link AgentEvent#getMetadata()}, which
     * carries the tool result's own metadata through unchanged.
     */
    private final String finalResultText;

    @JsonCreator
    public ToolResultEndEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("toolCallId") String toolCallId,
            @JsonProperty("toolCallName") String toolCallName,
            @JsonProperty("state") ToolResultState state,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("finalResultText") String finalResultText) {
        super(id, createdAt);
        this.replyId = replyId;
        this.toolCallId = toolCallId;
        this.toolCallName = toolCallName;
        this.state = state;
        this.finalResultText = finalResultText;
        this.withMetadata(metadata);
    }

    /**
     * Backward-compatible constructor for callers that do not provide metadata.
     */
    public ToolResultEndEvent(
            String id,
            String createdAt,
            String replyId,
            String toolCallId,
            String toolCallName,
            ToolResultState state) {
        this(id, createdAt, replyId, toolCallId, toolCallName, state, null, null);
    }

    public ToolResultEndEvent(
            String replyId, String toolCallId, String toolCallName, ToolResultState state) {
        this(replyId, toolCallId, toolCallName, state, null);
    }

    /**
     * As {@link #ToolResultEndEvent(String, String, String, ToolResultState)} additionally reporting
     * the tool method's return value.
     */
    public ToolResultEndEvent(
            String replyId,
            String toolCallId,
            String toolCallName,
            ToolResultState state,
            String finalResultText) {
        this.replyId = replyId;
        this.toolCallId = toolCallId;
        this.toolCallName = toolCallName;
        this.state = state;
        this.finalResultText = finalResultText;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.TOOL_RESULT_END;
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

    public ToolResultState getState() {
        return state;
    }

    /**
     * The tool method's return value as text, or {@code null} when the producer did not report one
     * (for example when the result carries no text blocks).
     */
    public String getFinalResultText() {
        return finalResultText;
    }
}
