/*
 * Copyright 2024-2025 the original author or authors.
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

package io.agentscope.core.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable generation options for LLM models.
 * Use the builder pattern to construct instances.
 */
public class GenerateOptions {
    private final Double temperature;
    private final Double topP;
    private final Integer maxTokens;
    private final Double frequencyPenalty;
    private final Double presencePenalty;
    private final Integer thinkingBudget;
    private final ResponseFormat responseFormat;
    private final Map<String, Object> additionalOptions;

    private GenerateOptions(Builder builder) {
        this.temperature = builder.temperature;
        this.topP = builder.topP;
        this.maxTokens = builder.maxTokens;
        this.frequencyPenalty = builder.frequencyPenalty;
        this.presencePenalty = builder.presencePenalty;
        this.thinkingBudget = builder.thinkingBudget;
        this.responseFormat = builder.responseFormat;
        this.additionalOptions =
                builder.additionalOptions != null
                        ? Collections.unmodifiableMap(new HashMap<>(builder.additionalOptions))
                        : Collections.emptyMap();
    }

    public Double getTemperature() {
        return temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    public Double getPresencePenalty() {
        return presencePenalty;
    }

    /**
     * Gets the maximum number of tokens for reasoning/thinking content.
     *
     * <p>This parameter is specific to models that support thinking mode (e.g., DashScope).
     * When set, it enables the model to show its reasoning process before generating the final
     * answer.
     *
     * @return the thinking budget in tokens, or null if not set
     */
    public Integer getThinkingBudget() {
        return thinkingBudget;
    }

    /**
     * Gets the response format configuration for structured output.
     *
     * <p>This parameter configures the model to return responses in a specific format,
     * such as JSON object or JSON schema-compliant structure.
     *
     * @return the response format, or null if not set
     */
    public ResponseFormat getResponseFormat() {
        return responseFormat;
    }

    public Map<String, Object> getAdditionalOptions() {
        return additionalOptions;
    }

    public Object getAdditionalOption(String key) {
        return additionalOptions.get(key);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Double temperature;
        private Double topP;
        private Integer maxTokens;
        private Double frequencyPenalty;
        private Double presencePenalty;
        private Integer thinkingBudget;
        private ResponseFormat responseFormat;
        private Map<String, Object> additionalOptions;

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder frequencyPenalty(Double frequencyPenalty) {
            this.frequencyPenalty = frequencyPenalty;
            return this;
        }

        public Builder presencePenalty(Double presencePenalty) {
            this.presencePenalty = presencePenalty;
            return this;
        }

        /**
         * Sets the thinking budget (maximum tokens for reasoning/thinking content).
         *
         * <p>This parameter is specific to models that support thinking mode. When set, the model
         * will show its reasoning process before generating the final answer. Setting this
         * parameter may automatically enable thinking mode in some models.
         *
         * @param thinkingBudget the maximum tokens for thinking content
         * @return this builder
         */
        public Builder thinkingBudget(Integer thinkingBudget) {
            this.thinkingBudget = thinkingBudget;
            return this;
        }

        /**
         * Sets the response format for structured output.
         *
         * <p>This configures the model to return responses in a specific format.
         * Use {@link ResponseFormat#jsonObject()} for simple JSON mode or
         * {@link ResponseFormat#jsonSchema(JsonSchemaSpec)} for schema-based structured output.
         *
         * @param responseFormat the response format configuration
         * @return this builder
         */
        public Builder responseFormat(ResponseFormat responseFormat) {
            this.responseFormat = responseFormat;
            return this;
        }

        public Builder additionalOption(String key, Object value) {
            if (this.additionalOptions == null) {
                this.additionalOptions = new HashMap<>();
            }
            this.additionalOptions.put(key, value);
            return this;
        }

        public GenerateOptions build() {
            return new GenerateOptions(this);
        }
    }
}
