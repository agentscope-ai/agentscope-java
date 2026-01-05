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
package io.agentscope.core.formatter.dashscope.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.util.JsonUtils;
import java.util.List;

/**
 * DashScope message DTO.
 *
 * <p>This class represents a message in the DashScope API format.
 * Content can be either a String (for text-only) or a List of
 * DashScopeContentPart (for multimodal).
 *
 * <p>Example text message:
 * <pre>{@code
 * {
 *   "role": "user",
 *   "content": "Hello"
 * }
 * }</pre>
 *
 * <p>Example multimodal message:
 * <pre>{@code
 * {
 *   "role": "user",
 *   "content": [
 *     {"text": "What's in this image?"},
 *     {"image": "https://example.com/image.jpg"}
 *   ]
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DashScopeMessage {

    /** Message role: "system", "user", "assistant", or "tool". */
    @JsonProperty("role")
    private String role;

    /**
     * Message content.
     * Can be String for text-only, or List&lt;DashScopeContentPart&gt; for multimodal.
     */
    @JsonProperty("content")
    private Object content;

    /** Tool name (for role="tool"). */
    @JsonProperty("name")
    private String name;

    /** Tool call ID (for role="tool"). */
    @JsonProperty("tool_call_id")
    private String toolCallId;

    /** Tool calls made by assistant. */
    @JsonProperty("tool_calls")
    private List<DashScopeToolCall> toolCalls;

    /** Reasoning/thinking content (for assistant messages with thinking enabled). */
    @JsonProperty("reasoning_content")
    private String reasoningContent;

    public DashScopeMessage() {}

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Object getContent() {
        return content;
    }

    public void setContent(Object content) {
        this.content = content;
    }

    /**
     * Get content as String (for text-only messages).
     *
     * @return the content as String, or null if multimodal
     */
    @JsonIgnore
    public String getContentAsString() {
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof List) {
            List<DashScopeContentPart> contentParts =
                    JsonUtils.getJsonCodec()
                            .convertValue(
                                    content, new TypeReference<List<DashScopeContentPart>>() {});
            if (contentParts != null && !contentParts.isEmpty()) {
                return contentParts.get(0).getText();
            }
        }

        return null;
    }

    /**
     * Get content as List (for multimodal messages).
     *
     * @return the content as List, or null if text-only
     */
    @JsonIgnore
    @SuppressWarnings("unchecked")
    public List<DashScopeContentPart> getContentAsList() {
        if (content instanceof List) {
            return (List<DashScopeContentPart>) content;
        }
        return null;
    }

    /**
     * Check if this message has multimodal content.
     *
     * @return true if content is a List
     */
    @JsonIgnore
    public boolean isMultimodal() {
        return content instanceof List;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public List<DashScopeToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<DashScopeToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public void setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final DashScopeMessage message = new DashScopeMessage();

        public Builder role(String role) {
            message.setRole(role);
            return this;
        }

        public Builder content(String content) {
            message.setContent(content);
            return this;
        }

        public Builder content(List<DashScopeContentPart> content) {
            message.setContent(content);
            return this;
        }

        public Builder name(String name) {
            message.setName(name);
            return this;
        }

        public Builder toolCallId(String toolCallId) {
            message.setToolCallId(toolCallId);
            return this;
        }

        public Builder toolCalls(List<DashScopeToolCall> toolCalls) {
            message.setToolCalls(toolCalls);
            return this;
        }

        public Builder reasoningContent(String reasoningContent) {
            message.setReasoningContent(reasoningContent);
            return this;
        }

        public DashScopeMessage build() {
            return message;
        }
    }
}
