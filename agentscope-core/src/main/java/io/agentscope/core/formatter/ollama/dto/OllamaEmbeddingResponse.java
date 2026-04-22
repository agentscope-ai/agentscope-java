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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Ollama embedding response DTO.
 * Represents the response from the Ollama embedding API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OllamaEmbeddingResponse {

    @JsonProperty("model")
    private String model;

    @JsonProperty("embeddings")
    private List<float[]> embeddings;

    @JsonProperty("total_duration")
    private Long totalDuration;

    @JsonProperty("load_duration")
    private Long loadDuration;

    @JsonProperty("prompt_eval_count")
    private Integer promptEvalCount;

    public OllamaEmbeddingResponse() {}

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<float[]> getEmbeddings() {
        return embeddings;
    }

    public void setEmbeddings(List<float[]> embeddings) {
        this.embeddings = embeddings;
    }

    public Long getTotalDuration() {
        return totalDuration;
    }

    public void setTotalDuration(Long totalDuration) {
        this.totalDuration = totalDuration;
    }

    public Long getLoadDuration() {
        return loadDuration;
    }

    public void setLoadDuration(Long loadDuration) {
        this.loadDuration = loadDuration;
    }

    public Integer getPromptEvalCount() {
        return promptEvalCount;
    }

    public void setPromptEvalCount(Integer promptEvalCount) {
        this.promptEvalCount = promptEvalCount;
    }
}
