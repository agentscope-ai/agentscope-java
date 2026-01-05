/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.formatter.ollama.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Ollama chat request DTO.
 * Represents the payload sent to the Ollama chat API.
 *
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OllamaRequest {

    /** The model name to use. */
    @JsonProperty("model")
    private String model;

    /** The list of messages in the conversation. */
    @JsonProperty("messages")
    private List<OllamaMessage> messages;

    /** Whether to stream the response. */
    @JsonProperty("stream")
    private Boolean stream;

    /** The format to return the response in (e.g., "json"). */
    @JsonProperty("format")
    private Object format;

    /** Controls how long the model will stay loaded into memory. */
    @JsonProperty("keep_alive")
    private String keepAlive;

    /** List of tools the model has access to. */
    @JsonProperty("tools")
    private List<OllamaTool> tools;

    /**
     * Tool choice configuration.
     * Can be "auto", "none", or a specific tool object.
     */
    @JsonProperty("tool_choice")
    private Object toolChoice;

    /**
     * Controls the thinking capability (Chain of Thought).
     * Can be boolean (enable/disable) or string (level).
     */
    @JsonProperty("think")
    private Object think;

    /** Model-specific options (temperature, etc.). */
    @JsonProperty("options")
    private Map<String, Object> options;

    public OllamaRequest() {}

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<OllamaMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<OllamaMessage> messages) {
        this.messages = messages;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    public Object getFormat() {
        return format;
    }

    public void setFormat(Object format) {
        this.format = format;
    }

    public String getKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(String keepAlive) {
        this.keepAlive = keepAlive;
    }

    public List<OllamaTool> getTools() {
        return tools;
    }

    public void setTools(List<OllamaTool> tools) {
        this.tools = tools;
    }

    public Object getToolChoice() {
        return toolChoice;
    }

    public void setToolChoice(Object toolChoice) {
        this.toolChoice = toolChoice;
    }

    public Object getThink() {
        return think;
    }

    public void setThink(Object think) {
        this.think = think;
    }

    public Map<String, Object> getOptions() {
        return options;
    }

    public void setOptions(Map<String, Object> options) {
        this.options = options;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final OllamaRequest request = new OllamaRequest();

        public Builder model(String model) {
            request.setModel(model);
            return this;
        }

        public Builder messages(List<OllamaMessage> messages) {
            request.setMessages(messages);
            return this;
        }

        public Builder stream(Boolean stream) {
            request.setStream(stream);
            return this;
        }

        public Builder format(Object format) {
            request.setFormat(format);
            return this;
        }

        public Builder keepAlive(String keepAlive) {
            request.setKeepAlive(keepAlive);
            return this;
        }

        public Builder tools(List<OllamaTool> tools) {
            request.setTools(tools);
            return this;
        }

        public Builder toolChoice(Object toolChoice) {
            request.setToolChoice(toolChoice);
            return this;
        }

        public Builder think(Object think) {
            request.setThink(think);
            return this;
        }

        public Builder options(Map<String, Object> options) {
            request.setOptions(options);
            return this;
        }

        public OllamaRequest build() {
            return request;
        }
    }
}
