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
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Represents an Anthropic API request.
 * Corresponds to MessageCreateParams in the SDK.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicRequest {

    @JsonProperty("model")
    private String model;

    @JsonProperty("messages")
    private List<AnthropicMessage> messages;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    @JsonProperty("temperature")
    private Double temperature;

    @JsonProperty("top_p")
    private Double topP;

    @JsonProperty("top_k")
    private Integer topK;

    @JsonProperty("system")
    private Object system; // Can be string or array of content blocks

    @JsonProperty("tools")
    private List<AnthropicTool> tools;

    @JsonProperty("tool_choice")
    private Object toolChoice; // Can be string ("auto", "any") or object with type and name

    @JsonProperty("stream")
    private Boolean stream;

    @JsonProperty("stop_sequences")
    private List<String> stopSequences;

    @JsonProperty("metadata")
    private Map<String, Object> metadata;

    public AnthropicRequest() {}

    // Getters and setters
    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<AnthropicMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<AnthropicMessage> messages) {
        this.messages = messages;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public Object getSystem() {
        return system;
    }

    public void setSystem(Object system) {
        this.system = system;
    }

    public List<AnthropicTool> getTools() {
        return tools;
    }

    public void setTools(List<AnthropicTool> tools) {
        this.tools = tools;
    }

    public Object getToolChoice() {
        return toolChoice;
    }

    public void setToolChoice(Object toolChoice) {
        this.toolChoice = toolChoice;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    public List<String> getStopSequences() {
        return stopSequences;
    }

    public void setStopSequences(List<String> stopSequences) {
        this.stopSequences = stopSequences;
    }

    public Map<String, Object> getMetadata() {
        return metadata != null ? Collections.unmodifiableMap(metadata) : null;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
