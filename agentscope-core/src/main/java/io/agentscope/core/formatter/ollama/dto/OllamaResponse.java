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

/**
 * Ollama chat response DTO.
 * Represents the response from the Ollama chat API.
 *
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OllamaResponse {

    /** The model name that generated the response. */
    @JsonProperty("model")
    private String model;

    /** The creation timestamp. */
    @JsonProperty("created_at")
    private String createdAt;

    /** The response message. */
    @JsonProperty("message")
    private OllamaMessage message;

    /** Whether the generation is complete. */
    @JsonProperty("done")
    private Boolean done;

    /** Time spent generating the response. */
    @JsonProperty("total_duration")
    private Long totalDuration;

    /** Time spent loading the model. */
    @JsonProperty("load_duration")
    private Long loadDuration;

    /** Number of tokens in the prompt. */
    @JsonProperty("prompt_eval_count")
    private Integer promptEvalCount;

    /** Time spent evaluating the prompt. */
    @JsonProperty("prompt_eval_duration")
    private Long promptEvalDuration;

    /** Number of tokens in the response. */
    @JsonProperty("eval_count")
    private Integer evalCount;

    /** Time spent generating the response. */
    @JsonProperty("eval_duration")
    private Long evalDuration;

    /** The reason why the generation stopped (e.g., "stop", "length"). */
    @JsonProperty("done_reason")
    private String doneReason;

    /** Error message if request failed. */
    @JsonProperty("error")
    private String error;

    public OllamaResponse() {}

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public OllamaMessage getMessage() {
        return message;
    }

    public void setMessage(OllamaMessage message) {
        this.message = message;
    }

    public Boolean getDone() {
        return done;
    }

    public void setDone(Boolean done) {
        this.done = done;
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

    public Long getPromptEvalDuration() {
        return promptEvalDuration;
    }

    public void setPromptEvalDuration(Long promptEvalDuration) {
        this.promptEvalDuration = promptEvalDuration;
    }

    public Integer getEvalCount() {
        return evalCount;
    }

    public void setEvalCount(Integer evalCount) {
        this.evalCount = evalCount;
    }

    public Long getEvalDuration() {
        return evalDuration;
    }

    public void setEvalDuration(Long evalDuration) {
        this.evalDuration = evalDuration;
    }

    public String getDoneReason() {
        return doneReason;
    }

    public void setDoneReason(String doneReason) {
        this.doneReason = doneReason;
    }
}
