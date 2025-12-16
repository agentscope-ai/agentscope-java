/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.formatter.gemini.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Gemini Generation Config DTO.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GeminiGenerationConfig {

    @JsonProperty("stopSequences")
    private List<String> stopSequences;

    @JsonProperty("responseMimeType")
    private String responseMimeType;

    @JsonProperty("responseSchema")
    private Object responseSchema;

    @JsonProperty("candidateCount")
    private Integer candidateCount;

    @JsonProperty("maxOutputTokens")
    private Integer maxOutputTokens;

    @JsonProperty("temperature")
    private Double temperature;

    @JsonProperty("topP")
    private Double topP;

    @JsonProperty("topK")
    private Double topK; // Gemini uses number (double) or integer for topK, float in SDK

    @JsonProperty("presencePenalty")
    private Double presencePenalty;

    @JsonProperty("frequencyPenalty")
    private Double frequencyPenalty;

    @JsonProperty("seed")
    private Integer seed;

    @JsonProperty("thinkingConfig")
    private GeminiThinkingConfig thinkingConfig;

    // Getters and Builders

    public static Builder builder() {
        return new Builder();
    }

    public List<String> getStopSequences() {
        return stopSequences;
    }

    public void setStopSequences(List<String> stopSequences) {
        this.stopSequences = stopSequences;
    }

    public String getResponseMimeType() {
        return responseMimeType;
    }

    public void setResponseMimeType(String responseMimeType) {
        this.responseMimeType = responseMimeType;
    }

    public Object getResponseSchema() {
        return responseSchema;
    }

    public void setResponseSchema(Object responseSchema) {
        this.responseSchema = responseSchema;
    }

    public Integer getCandidateCount() {
        return candidateCount;
    }

    public void setCandidateCount(Integer candidateCount) {
        this.candidateCount = candidateCount;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(Integer maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
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

    public Double getTopK() {
        return topK;
    }

    public void setTopK(Double topK) {
        this.topK = topK;
    }

    public Double getPresencePenalty() {
        return presencePenalty;
    }

    public void setPresencePenalty(Double presencePenalty) {
        this.presencePenalty = presencePenalty;
    }

    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    public void setFrequencyPenalty(Double frequencyPenalty) {
        this.frequencyPenalty = frequencyPenalty;
    }

    public Integer getSeed() {
        return seed;
    }

    public void setSeed(Integer seed) {
        this.seed = seed;
    }

    public GeminiThinkingConfig getThinkingConfig() {
        return thinkingConfig;
    }

    public void setThinkingConfig(GeminiThinkingConfig thinkingConfig) {
        this.thinkingConfig = thinkingConfig;
    }

    public static class Builder {
        private final GeminiGenerationConfig config = new GeminiGenerationConfig();

        public Builder stopSequences(List<String> stopSequences) {
            config.stopSequences = stopSequences;
            return this;
        }

        public Builder responseMimeType(String responseMimeType) {
            config.responseMimeType = responseMimeType;
            return this;
        }

        public Builder responseSchema(Object responseSchema) {
            config.responseSchema = responseSchema;
            return this;
        }

        public Builder candidateCount(Integer candidateCount) {
            config.candidateCount = candidateCount;
            return this;
        }

        public Builder maxOutputTokens(Integer maxOutputTokens) {
            config.maxOutputTokens = maxOutputTokens;
            return this;
        }

        public Builder temperature(Double temperature) {
            config.temperature = temperature;
            return this;
        }

        public Builder topP(Double topP) {
            config.topP = topP;
            return this;
        }

        public Builder topK(Double topK) {
            config.topK = topK;
            return this;
        }

        public Builder presencePenalty(Double presencePenalty) {
            config.presencePenalty = presencePenalty;
            return this;
        }

        public Builder frequencyPenalty(Double frequencyPenalty) {
            config.frequencyPenalty = frequencyPenalty;
            return this;
        }

        public Builder seed(Integer seed) {
            config.seed = seed;
            return this;
        }

        public Builder thinkingConfig(GeminiThinkingConfig thinkingConfig) {
            config.thinkingConfig = thinkingConfig;
            return this;
        }

        public GeminiGenerationConfig build() {
            return config;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GeminiThinkingConfig {
        @JsonProperty("includeThoughts")
        private Boolean includeThoughts;

        @JsonProperty("thinkingBudget")
        private Integer thinkingBudget;

        public static Builder builder() {
            return new Builder();
        }

        public Boolean getIncludeThoughts() {
            return includeThoughts;
        }

        public void setIncludeThoughts(Boolean includeThoughts) {
            this.includeThoughts = includeThoughts;
        }

        public Integer getThinkingBudget() {
            return thinkingBudget;
        }

        public void setThinkingBudget(Integer thinkingBudget) {
            this.thinkingBudget = thinkingBudget;
        }

        public static class Builder {
            private GeminiThinkingConfig config = new GeminiThinkingConfig();

            public Builder includeThoughts(Boolean includeThoughts) {
                config.includeThoughts = includeThoughts;
                return this;
            }

            public Builder thinkingBudget(Integer thinkingBudget) {
                config.thinkingBudget = thinkingBudget;
                return this;
            }

            public GeminiThinkingConfig build() {
                return config;
            }
        }
    }
}
