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
package io.agentscope.core.formatter.anthropic.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Represents a content block in an Anthropic message.
 * Can be text, image, tool_use, or tool_result.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicContent {

    @JsonProperty("type")
    private String type;

    // For text content
    @JsonProperty("text")
    private String text;

    // For image content
    @JsonProperty("source")
    private ImageSource source;

    // For tool_use content
    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("input")
    private Map<String, Object> input;

    // For tool_result content
    @JsonProperty("tool_use_id")
    private String toolUseId;

    @JsonProperty("content")
    private Object content; // Can be string or array of content blocks

    @JsonProperty("is_error")
    private Boolean isError;

    // For thinking content (extended thinking)
    @JsonProperty("thinking")
    private String thinking;

    public AnthropicContent() {}

    public static AnthropicContent text(String text) {
        AnthropicContent content = new AnthropicContent();
        content.type = "text";
        content.text = text;
        return content;
    }

    public static AnthropicContent image(String mediaType, String data) {
        AnthropicContent content = new AnthropicContent();
        content.type = "image";
        content.source = new ImageSource(mediaType, data);
        return content;
    }

    public static AnthropicContent toolUse(String id, String name, Map<String, Object> input) {
        AnthropicContent content = new AnthropicContent();
        content.type = "tool_use";
        content.id = id;
        content.name = name;
        content.input = input;
        return content;
    }

    public static AnthropicContent toolResult(String toolUseId, Object content, Boolean isError) {
        AnthropicContent result = new AnthropicContent();
        result.type = "tool_result";
        result.toolUseId = toolUseId;
        result.content = content;
        result.isError = isError;
        return result;
    }

    public static AnthropicContent thinking(String thinking) {
        AnthropicContent content = new AnthropicContent();
        content.type = "thinking";
        content.thinking = thinking;
        return content;
    }

    // Getters and setters
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public ImageSource getSource() {
        return source;
    }

    public void setSource(ImageSource source) {
        this.source = source;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public void setInput(Map<String, Object> input) {
        this.input = input;
    }

    public String getToolUseId() {
        return toolUseId;
    }

    public void setToolUseId(String toolUseId) {
        this.toolUseId = toolUseId;
    }

    public Object getContent() {
        return content;
    }

    public void setContent(Object content) {
        this.content = content;
    }

    public Boolean getIsError() {
        return isError;
    }

    public void setIsError(Boolean isError) {
        this.isError = isError;
    }

    public String getThinking() {
        return thinking;
    }

    public void setThinking(String thinking) {
        this.thinking = thinking;
    }

    /**
     * Image source for image content blocks.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ImageSource {
        @JsonProperty("type")
        private String type = "base64";

        @JsonProperty("media_type")
        private String mediaType;

        @JsonProperty("data")
        private String data;

        public ImageSource() {}

        public ImageSource(String mediaType, String data) {
            this.mediaType = mediaType;
            this.data = data;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getMediaType() {
            return mediaType;
        }

        public void setMediaType(String mediaType) {
            this.mediaType = mediaType;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }
    }
}
