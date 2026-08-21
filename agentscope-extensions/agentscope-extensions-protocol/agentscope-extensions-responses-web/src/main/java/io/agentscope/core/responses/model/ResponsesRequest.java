/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.responses.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Request payload for the OpenAI Responses-compatible HTTP API. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponsesRequest {

    private String model;
    private Object input;
    private String instructions;
    private Boolean stream;
    private List<ResponsesTool> tools;

    @JsonProperty("tool_choice")
    private Object toolChoice;

    private Double temperature;

    @JsonProperty("top_p")
    private Double topP;

    @JsonProperty("max_output_tokens")
    private Integer maxOutputTokens;

    private ResponsesReasoningConfig reasoning;
    private ResponsesTextConfig text;

    @JsonProperty("previous_response_id")
    private String previousResponseId;

    private Object conversation;
    private Boolean background;
    private Boolean store;
    private Map<String, Object> metadata;

    private final Map<String, Object> additionalFields = new LinkedHashMap<>();

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Object getInput() {
        return input;
    }

    public void setInput(Object input) {
        this.input = input;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    public List<ResponsesTool> getTools() {
        return tools;
    }

    public void setTools(List<ResponsesTool> tools) {
        this.tools = tools;
    }

    public Object getToolChoice() {
        return toolChoice;
    }

    public void setToolChoice(Object toolChoice) {
        this.toolChoice = toolChoice;
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

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(Integer maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public ResponsesReasoningConfig getReasoning() {
        return reasoning;
    }

    public void setReasoning(ResponsesReasoningConfig reasoning) {
        this.reasoning = reasoning;
    }

    public ResponsesTextConfig getText() {
        return text;
    }

    public void setText(ResponsesTextConfig text) {
        this.text = text;
    }

    public String getPreviousResponseId() {
        return previousResponseId;
    }

    public void setPreviousResponseId(String previousResponseId) {
        this.previousResponseId = previousResponseId;
    }

    public Object getConversation() {
        return conversation;
    }

    public void setConversation(Object conversation) {
        this.conversation = conversation;
    }

    public Boolean getBackground() {
        return background;
    }

    public void setBackground(Boolean background) {
        this.background = background;
    }

    public Boolean getStore() {
        return store;
    }

    public void setStore(Boolean store) {
        this.store = store;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    @JsonAnyGetter
    public Map<String, Object> getAdditionalFields() {
        return additionalFields;
    }

    @JsonAnySetter
    public void putAdditionalField(String name, Object value) {
        additionalFields.put(name, value);
    }
}
