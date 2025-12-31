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
package io.agentscope.core.formatter.openai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * OpenAI message DTO.
 *
 * <p>This class represents a message in the OpenAI Chat Completion API format.
 * Content can be either a String (for text-only) or a List of
 * OpenAIContentPart (for multimodal).
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
 *     {"type": "text", "text": "What's in this image?"},
 *     {"type": "image_url", "image_url": {"url": "https://example.com/image.jpg"}}
 *   ]
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAIMessage {

    /** Message role: "system", "user", "assistant", or "tool". */
    @JsonProperty("role")
    private String role;

    /**
     * Message content.
     * Can be String for text-only, or List&lt;OpenAIContentPart&gt; for multimodal.
     */
    @JsonProperty("content")
    private Object content;

    /** Name of the author of this message (optional). */
    @JsonProperty("name")
    private String name;

    /** Tool call ID (for role="tool"). */
    @JsonProperty("tool_call_id")
    private String toolCallId;

    /** Tool calls made by assistant. */
    @JsonProperty("tool_calls")
    private List<OpenAIToolCall> toolCalls;

    /**
     * Reasoning/thinking content (for o1 and similar reasoning models).
     * This field contains the model's internal reasoning process.
     */
    @JsonProperty("reasoning_content")
    private String reasoningContent;

    /**
     * Reasoning details (OpenRouter specific for Gemini).
     */
    @JsonProperty("reasoning_details")
    private List<OpenAIReasoningDetail> reasoningDetails;

    /**
     * Refusal message (for content policy violations).
     */
    @JsonProperty("refusal")
    private String refusal;

    public OpenAIMessage() {}

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

    public List<OpenAIToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<OpenAIToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public void setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
    }

    public List<OpenAIReasoningDetail> getReasoningDetails() {
        return reasoningDetails;
    }

    public void setReasoningDetails(List<OpenAIReasoningDetail> reasoningDetails) {
        this.reasoningDetails = reasoningDetails;
    }

    public String getRefusal() {
        return refusal;
    }

    public void setRefusal(String refusal) {
        this.refusal = refusal;
    }

    /**
     * Get content as String (for text-only messages).
     *
     * @return the content as String, or null if multimodal
     */
    @JsonIgnore
    public String getContentAsString() {
        if (content instanceof String) {
            return (String) content;
        } else if (content instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object item : (List<?>) content) {
                if (item instanceof OpenAIContentPart) {
                    OpenAIContentPart part = (OpenAIContentPart) item;
                    if ("text".equals(part.getType()) && part.getText() != null) {
                        sb.append(part.getText());
                    }
                }
            }
            return sb.toString();
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
    public List<OpenAIContentPart> getContentAsList() {
        if (content instanceof List) {
            return (List<OpenAIContentPart>) content;
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

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private OpenAIMessage message = new OpenAIMessage();

        public Builder role(String role) {
            message.setRole(role);
            return this;
        }

        public Builder content(String content) {
            message.setContent(content);
            return this;
        }

        public Builder content(List<OpenAIContentPart> content) {
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

        public Builder toolCalls(List<OpenAIToolCall> toolCalls) {
            message.setToolCalls(toolCalls);
            return this;
        }

        public Builder reasoningContent(String reasoningContent) {
            message.setReasoningContent(reasoningContent);
            return this;
        }

        public Builder reasoningDetails(List<OpenAIReasoningDetail> reasoningDetails) {
            message.setReasoningDetails(reasoningDetails);
            return this;
        }

        public Builder refusal(String refusal) {
            message.setRefusal(refusal);
            return this;
        }

        public OpenAIMessage build() {
            OpenAIMessage result = message;
            message = new OpenAIMessage();
            return result;
        }
    }
}
