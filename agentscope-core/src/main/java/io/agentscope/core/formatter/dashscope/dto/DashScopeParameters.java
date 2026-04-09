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
package io.agentscope.core.formatter.dashscope.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.formatter.ResponseFormat;
import java.util.List;

/**
 * DashScope API parameters DTO.
 *
 * <p>This class contains generation parameters for DashScope API requests.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashScopeParameters {

    /** Result format, should be "message" for chat completions. */
    @JsonProperty("result_format")
    private String resultFormat = "message";

    /** Whether to use incremental output for streaming. */
    @JsonProperty("incremental_output")
    private Boolean incrementalOutput;

    /** Sampling temperature (0.0-2.0). */
    @JsonProperty("temperature")
    private Double temperature;

    /** Nucleus sampling parameter (0.0-1.0). */
    @JsonProperty("top_p")
    private Double topP;

    /** Top-K sampling parameter. */
    @JsonProperty("top_k")
    private Integer topK;

    /** Maximum tokens to generate. */
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    /** Stop sequences. */
    @JsonProperty("stop")
    private List<String> stop;

    /** Enable thinking/reasoning mode. */
    @JsonProperty("enable_thinking")
    private Boolean enableThinking;

    /** Enable search mode. */
    @JsonProperty("enable_search")
    private Boolean enableSearch;

    /** Token budget for thinking. */
    @JsonProperty("thinking_budget")
    private Integer thinkingBudget;

    /** List of available tools. */
    @JsonProperty("tools")
    private List<DashScopeTool> tools;

    /**
     * Tool choice configuration.
     * Can be "auto", "none", or a specific tool object.
     */
    @JsonProperty("tool_choice")
    private Object toolChoice;

    /** Random seed for reproducibility. */
    @JsonProperty("seed")
    private Integer seed;

    /** Frequency penalty (-2.0 to 2.0). */
    @JsonProperty("frequency_penalty")
    private Double frequencyPenalty;

    /** Presence penalty (-2.0 to 2.0). */
    @JsonProperty("presence_penalty")
    private Double presencePenalty;

    /** Repetition penalty (0.0 to 2.0). */
    @JsonProperty("repetition_penalty")
    private Double repetitionPenalty;

    /** The configuration for the llm response format. */
    @JsonProperty("response_format")
    ResponseFormat responseFormat;

    public DashScopeParameters() {}

    public String getResultFormat() {
        return resultFormat;
    }

    public void setResultFormat(String resultFormat) {
        this.resultFormat = resultFormat;
    }

    public Boolean getIncrementalOutput() {
        return incrementalOutput;
    }

    public void setIncrementalOutput(Boolean incrementalOutput) {
        this.incrementalOutput = incrementalOutput;
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

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public List<String> getStop() {
        return stop;
    }

    public void setStop(List<String> stop) {
        this.stop = stop;
    }

    public Boolean getEnableThinking() {
        return enableThinking;
    }

    public void setEnableThinking(Boolean enableThinking) {
        this.enableThinking = enableThinking;
    }

    public Integer getThinkingBudget() {
        return thinkingBudget;
    }

    public void setThinkingBudget(Integer thinkingBudget) {
        this.thinkingBudget = thinkingBudget;
    }

    public Boolean getEnableSearch() {
        return enableSearch;
    }

    public void setEnableSearch(Boolean enableSearch) {
        this.enableSearch = enableSearch;
    }

    public List<DashScopeTool> getTools() {
        return tools;
    }

    public void setTools(List<DashScopeTool> tools) {
        this.tools = tools;
    }

    public Object getToolChoice() {
        return toolChoice;
    }

    public void setToolChoice(Object toolChoice) {
        this.toolChoice = toolChoice;
    }

    public Integer getSeed() {
        return seed;
    }

    public void setSeed(Integer seed) {
        this.seed = seed;
    }

    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    public void setFrequencyPenalty(Double frequencyPenalty) {
        this.frequencyPenalty = frequencyPenalty;
    }

    public Double getPresencePenalty() {
        return presencePenalty;
    }

    public void setPresencePenalty(Double presencePenalty) {
        this.presencePenalty = presencePenalty;
    }

    public Double getRepetitionPenalty() {
        return repetitionPenalty;
    }

    public void setRepetitionPenalty(Double repetitionPenalty) {
        this.repetitionPenalty = repetitionPenalty;
    }

    public ResponseFormat getResponseFormat() {
        return responseFormat;
    }

    public void setResponseFormat(ResponseFormat responseFormat) {
        this.responseFormat = responseFormat;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final DashScopeParameters params = new DashScopeParameters();

        public Builder resultFormat(String resultFormat) {
            params.setResultFormat(resultFormat);
            return this;
        }

        public Builder incrementalOutput(Boolean incrementalOutput) {
            params.setIncrementalOutput(incrementalOutput);
            return this;
        }

        public Builder temperature(Double temperature) {
            params.setTemperature(temperature);
            return this;
        }

        public Builder topP(Double topP) {
            params.setTopP(topP);
            return this;
        }

        public Builder topK(Integer topK) {
            params.setTopK(topK);
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            params.setMaxTokens(maxTokens);
            return this;
        }

        public Builder stop(List<String> stop) {
            params.setStop(stop);
            return this;
        }

        public Builder enableThinking(Boolean enableThinking) {
            params.setEnableThinking(enableThinking);
            return this;
        }

        public Builder thinkingBudget(Integer thinkingBudget) {
            params.setThinkingBudget(thinkingBudget);
            return this;
        }

        public Builder tools(List<DashScopeTool> tools) {
            params.setTools(tools);
            return this;
        }

        public Builder toolChoice(Object toolChoice) {
            params.setToolChoice(toolChoice);
            return this;
        }

        public Builder seed(Integer seed) {
            params.setSeed(seed);
            return this;
        }

        public Builder frequencyPenalty(Double frequencyPenalty) {
            params.setFrequencyPenalty(frequencyPenalty);
            return this;
        }

        public Builder presencePenalty(Double presencePenalty) {
            params.setPresencePenalty(presencePenalty);
            return this;
        }

        public Builder repetitionPenalty(Double repetitionPenalty) {
            params.setRepetitionPenalty(repetitionPenalty);
            return this;
        }

        public Builder responseFormat(ResponseFormat responseFormat) {
            params.setResponseFormat(responseFormat);
            return this;
        }

        public DashScopeParameters build() {
            return params;
        }
    }
}
