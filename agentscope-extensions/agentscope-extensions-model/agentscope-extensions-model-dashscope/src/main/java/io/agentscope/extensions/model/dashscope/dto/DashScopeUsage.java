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
package io.agentscope.extensions.model.dashscope.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DashScope usage statistics DTO.
 *
 * <p>This class represents token usage statistics in a DashScope API response.
 *
 * <p>Example JSON:
 * <pre>{@code
 * {
 *   "input_tokens": 10,
 *   "output_tokens": 20,
 *   "total_tokens": 30
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DashScopeUsage {

    /** Number of tokens in the input. */
    @JsonProperty("input_tokens")
    private Integer inputTokens;

    /** Number of tokens in the output. */
    @JsonProperty("output_tokens")
    private Integer outputTokens;

    /** Total tokens (input + output). */
    @JsonProperty("total_tokens")
    private Integer totalTokens;

    /** Image tokens (for multimodal). */
    @JsonProperty("image_tokens")
    private Integer imageTokens;

    /** Video tokens (for multimodal). */
    @JsonProperty("video_tokens")
    private Integer videoTokens;

    /** Audio tokens (for multimodal). */
    @JsonProperty("audio_tokens")
    private Integer audioTokens;

    /** Legacy regional field for cache hits. */
    @JsonProperty("cached_tokens")
    private Integer cachedTokens;

    /** Legacy/top-level field for explicit cache creation. */
    @JsonProperty("cache_creation_input_tokens")
    private Integer cacheCreationInputTokens;

    /** Current DashScope prompt token details. */
    @JsonProperty("prompt_tokens_details")
    private DashScopeTokenDetails promptTokensDetails;

    /** Alternate token detail shape returned by some endpoints. */
    @JsonProperty("input_tokens_details")
    private DashScopeTokenDetails inputTokensDetails;

    public DashScopeUsage() {}

    public Integer getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Integer inputTokens) {
        this.inputTokens = inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Integer outputTokens) {
        this.outputTokens = outputTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Integer getImageTokens() {
        return imageTokens;
    }

    public void setImageTokens(Integer imageTokens) {
        this.imageTokens = imageTokens;
    }

    public Integer getVideoTokens() {
        return videoTokens;
    }

    public void setVideoTokens(Integer videoTokens) {
        this.videoTokens = videoTokens;
    }

    public Integer getAudioTokens() {
        return audioTokens;
    }

    public void setAudioTokens(Integer audioTokens) {
        this.audioTokens = audioTokens;
    }

    /** Returns cache hits across current and legacy DashScope response shapes. */
    public Integer getCachedTokens() {
        if (promptTokensDetails != null && promptTokensDetails.getCachedTokens() != null) {
            return promptTokensDetails.getCachedTokens();
        }
        if (inputTokensDetails != null && inputTokensDetails.getCachedTokens() != null) {
            return inputTokensDetails.getCachedTokens();
        }
        return cachedTokens;
    }

    public void setCachedTokens(Integer cachedTokens) {
        this.cachedTokens = cachedTokens;
    }

    /** Returns explicit cache creation tokens across current and legacy response shapes. */
    public Integer getCacheCreationInputTokens() {
        if (promptTokensDetails != null
                && promptTokensDetails.getCacheCreationInputTokens() != null) {
            return promptTokensDetails.getCacheCreationInputTokens();
        }
        if (inputTokensDetails != null
                && inputTokensDetails.getCacheCreationInputTokens() != null) {
            return inputTokensDetails.getCacheCreationInputTokens();
        }
        return cacheCreationInputTokens;
    }

    public void setCacheCreationInputTokens(Integer cacheCreationInputTokens) {
        this.cacheCreationInputTokens = cacheCreationInputTokens;
    }

    public DashScopeTokenDetails getPromptTokensDetails() {
        return promptTokensDetails;
    }

    public void setPromptTokensDetails(DashScopeTokenDetails promptTokensDetails) {
        this.promptTokensDetails = promptTokensDetails;
    }

    public DashScopeTokenDetails getInputTokensDetails() {
        return inputTokensDetails;
    }

    public void setInputTokensDetails(DashScopeTokenDetails inputTokensDetails) {
        this.inputTokensDetails = inputTokensDetails;
    }
}
