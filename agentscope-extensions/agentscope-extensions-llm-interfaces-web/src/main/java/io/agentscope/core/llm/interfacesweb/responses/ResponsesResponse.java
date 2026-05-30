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
package io.agentscope.core.llm.interfacesweb.responses;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Response body for the stateless OpenAI Responses-compatible endpoint. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponsesResponse {

    private String id;
    private String object = "response";
    private String status;
    private String model;
    private List<ResponsesOutputItem> output;
    private ResponsesUsage usage;
    private Object error;

    @JsonProperty("created_at")
    private long createdAt;

    @JsonProperty("output_text")
    private String outputText;

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

    public List<ResponsesOutputItem> getOutput() {
        return output;
    }

    public void setOutput(List<ResponsesOutputItem> output) {
        this.output = output;
    }

    public ResponsesUsage getUsage() {
        return usage;
    }

    public void setUsage(ResponsesUsage usage) {
        this.usage = usage;
    }

    public Object getError() {
        return error;
    }

    public void setError(Object error) {
        this.error = error;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getOutputText() {
        return outputText;
    }

    public void setOutputText(String outputText) {
        this.outputText = outputText;
    }
}
