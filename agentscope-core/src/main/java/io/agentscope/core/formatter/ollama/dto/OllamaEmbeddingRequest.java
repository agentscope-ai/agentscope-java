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
 * Ollama embedding request DTO.
 * Represents the payload sent to the Ollama embedding API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OllamaEmbeddingRequest {

    @JsonProperty("model")
    private String model;

    @JsonProperty("input")
    private List<String> input;

    @JsonProperty("keep_alive")
    private String keepAlive;

    @JsonProperty("truncate")
    private Boolean truncate;

    @JsonProperty("options")
    private Map<String, Object> options;

    public OllamaEmbeddingRequest() {}

    public OllamaEmbeddingRequest(String model, List<String> input) {
        this.model = model;
        this.input = input;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<String> getInput() {
        return input;
    }

    public void setInput(List<String> input) {
        this.input = input;
    }

    public String getKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(String keepAlive) {
        this.keepAlive = keepAlive;
    }

    public Boolean getTruncate() {
        return truncate;
    }

    public void setTruncate(Boolean truncate) {
        this.truncate = truncate;
    }

    public Map<String, Object> getOptions() {
        return options;
    }

    public void setOptions(Map<String, Object> options) {
        this.options = options;
    }
}
