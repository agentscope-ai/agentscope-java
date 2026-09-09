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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** Non-streaming Responses API response object. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponsesResponse {

    private String id;
    private String object = "response";

    @JsonProperty("created_at")
    private long createdAt;

    private String status;
    private String model;
    private String instructions;
    private List<ResponsesOutputItem> output;

    @JsonProperty("output_text")
    private String outputText;

    private Boolean store = false;

    @JsonProperty("previous_response_id")
    private String previousResponseId;

    private Object conversation;
    private Boolean background;
    private Map<String, Object> metadata;
    private ResponsesTextConfig text;

    @JsonProperty("tool_choice")
    private Object toolChoice;

    private List<ResponsesTool> tools;
    private ResponsesUsage usage;
    private ResponsesError error;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public List<ResponsesOutputItem> getOutput() {
        return output;
    }

    public void setOutput(List<ResponsesOutputItem> output) {
        this.output = output;
    }

    public String getOutputText() {
        return outputText;
    }

    public void setOutputText(String outputText) {
        this.outputText = outputText;
    }

    public Boolean getStore() {
        return store;
    }

    public void setStore(Boolean store) {
        this.store = store;
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

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public ResponsesTextConfig getText() {
        return text;
    }

    public void setText(ResponsesTextConfig text) {
        this.text = text;
    }

    public Object getToolChoice() {
        return toolChoice;
    }

    public void setToolChoice(Object toolChoice) {
        this.toolChoice = toolChoice;
    }

    public List<ResponsesTool> getTools() {
        return tools;
    }

    public void setTools(List<ResponsesTool> tools) {
        this.tools = tools;
    }

    public ResponsesUsage getUsage() {
        return usage;
    }

    public void setUsage(ResponsesUsage usage) {
        this.usage = usage;
    }

    public ResponsesError getError() {
        return error;
    }

    public void setError(ResponsesError error) {
        this.error = error;
    }
}
