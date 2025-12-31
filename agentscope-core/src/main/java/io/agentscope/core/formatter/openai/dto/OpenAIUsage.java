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
package io.agentscope.core.formatter.openai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI usage statistics DTO.
 *
 * <p>This class represents token usage statistics in an OpenAI API response.
 *
 * <p>Example JSON:
 * <pre>{@code
 * {
 *   "prompt_tokens": 10,
 *   "completion_tokens": 20,
 *   "total_tokens": 30
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAIUsage {

    /** Number of tokens in the prompt. */
    @JsonProperty("prompt_tokens")
    private Integer promptTokens;

    /** Number of tokens in the completion. */
    @JsonProperty("completion_tokens")
    private Integer completionTokens;

    /** Total tokens (prompt + completion). */
    @JsonProperty("total_tokens")
    private Integer totalTokens;

    /** Detailed breakdown of prompt tokens (optional). */
    @JsonProperty("prompt_tokens_details")
    private PromptTokensDetails promptTokensDetails;

    /** Detailed breakdown of completion tokens (optional). */
    @JsonProperty("completion_tokens_details")
    private CompletionTokensDetails completionTokensDetails;

    public OpenAIUsage() {}

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public PromptTokensDetails getPromptTokensDetails() {
        return promptTokensDetails;
    }

    public void setPromptTokensDetails(PromptTokensDetails promptTokensDetails) {
        this.promptTokensDetails = promptTokensDetails;
    }

    public CompletionTokensDetails getCompletionTokensDetails() {
        return completionTokensDetails;
    }

    public void setCompletionTokensDetails(CompletionTokensDetails completionTokensDetails) {
        this.completionTokensDetails = completionTokensDetails;
    }

    /**
     * Detailed breakdown of prompt tokens.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PromptTokensDetails {
        @JsonProperty("cached_tokens")
        private Integer cachedTokens;

        @JsonProperty("audio_tokens")
        private Integer audioTokens;

        public Integer getCachedTokens() {
            return cachedTokens;
        }

        public void setCachedTokens(Integer cachedTokens) {
            this.cachedTokens = cachedTokens;
        }

        public Integer getAudioTokens() {
            return audioTokens;
        }

        public void setAudioTokens(Integer audioTokens) {
            this.audioTokens = audioTokens;
        }
    }

    /**
     * Detailed breakdown of completion tokens.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CompletionTokensDetails {
        @JsonProperty("reasoning_tokens")
        private Integer reasoningTokens;

        @JsonProperty("audio_tokens")
        private Integer audioTokens;

        @JsonProperty("accepted_prediction_tokens")
        private Integer acceptedPredictionTokens;

        @JsonProperty("rejected_prediction_tokens")
        private Integer rejectedPredictionTokens;

        public Integer getReasoningTokens() {
            return reasoningTokens;
        }

        public void setReasoningTokens(Integer reasoningTokens) {
            this.reasoningTokens = reasoningTokens;
        }

        public Integer getAudioTokens() {
            return audioTokens;
        }

        public void setAudioTokens(Integer audioTokens) {
            this.audioTokens = audioTokens;
        }

        public Integer getAcceptedPredictionTokens() {
            return acceptedPredictionTokens;
        }

        public void setAcceptedPredictionTokens(Integer acceptedPredictionTokens) {
            this.acceptedPredictionTokens = acceptedPredictionTokens;
        }

        public Integer getRejectedPredictionTokens() {
            return rejectedPredictionTokens;
        }

        public void setRejectedPredictionTokens(Integer rejectedPredictionTokens) {
            this.rejectedPredictionTokens = rejectedPredictionTokens;
        }
    }
}
