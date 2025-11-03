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
    private final ExecutionConfig executionConfig;
    private final Map<String, Object> additionalOptions;

    /**
     * Creates a new GenerateOptions instance using the builder pattern.
     *
     * @param builder the builder containing the generation options configuration
     */
    private GenerateOptions(Builder builder) {
        this.temperature = builder.temperature;
        this.topP = builder.topP;
        this.maxTokens = builder.maxTokens;
        this.frequencyPenalty = builder.frequencyPenalty;
        this.presencePenalty = builder.presencePenalty;
        this.thinkingBudget = builder.thinkingBudget;
        this.executionConfig = builder.executionConfig;
        this.additionalOptions =
                builder.additionalOptions != null
                        ? Collections.unmodifiableMap(new HashMap<>(builder.additionalOptions))
                        : Collections.emptyMap();
    }

    /**
     * Gets the temperature for text generation.
     *
     * <p>Higher values (e.g., 0.8) make output more random, while lower values
     * (e.g., 0.2) make it more focused and deterministic.
     *
     * @return the temperature value between 0 and 2, or null if not set
     */
    public Double getTemperature() {
        return temperature;
    }

    /**
     * Gets the top-p (nucleus sampling) parameter.
     *
     * <p>Controls diversity via nucleus sampling: considers the smallest set of tokens
     * whose cumulative probability exceeds the top_p value.
     *
     * @return the top-p value between 0 and 1, or null if not set
     */
    public Double getTopP() {
        return topP;
    }

    /**
     * Gets the maximum number of tokens to generate.
     *
     * @return the maximum tokens limit, or null if not set
     */
    public Integer getMaxTokens() {
        return maxTokens;
    }

    /**
     * Gets the frequency penalty.
     *
     * <p>Reduces repetition by penalizing tokens based on their frequency in the text so far.
     * Higher values decrease repetition more strongly.
     *
     * @return the frequency penalty between -2 and 2, or null if not set
     */
    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    /**
     * Gets the presence penalty.
     *
     * <p>Reduces repetition by penalizing tokens that have already appeared in the text.
     * Higher values decrease repetition more strongly.
     *
     * @return the presence penalty between -2 and 2, or null if not set
     */
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
     * Gets the execution configuration for timeout and retry behavior.
     *
     * <p>When set, the model will apply timeout and retry logic according to the
     * configured execution config (timeout duration, max attempts, backoff, error filtering).
     *
     * @return the execution configuration, or null if not configured
     */
    public ExecutionConfig getExecutionConfig() {
        return executionConfig;
    }

    /**
     * Gets the additional options map.
     *
     * @return an unmodifiable map of additional options
     */
    public Map<String, Object> getAdditionalOptions() {
        return additionalOptions;
    }

    /**
     * Gets a specific additional option by key.
     *
     * @param key the option key
     * @return the option value, or null if not found
     */
    public Object getAdditionalOption(String key) {
        return additionalOptions.get(key);
    }

    /**
     * Creates a new builder for GenerateOptions.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Merges two GenerateOptions instances, with primary options taking precedence.
     *
     * <p>This method performs parameter-by-parameter merging: for each parameter, if the primary
     * value is non-null, it is used; otherwise, the fallback value is used. This allows proper
     * layering of options from different sources (e.g., per-request options over default options).
     *
     * <p><b>Merge Behavior:</b>
     * <ul>
     *   <li>Primitive fields (temperature, topP, etc.): primary != null ? primary : fallback</li>
     *   <li>additionalOptions: merges both maps, with primary values overriding fallback</li>
     *   <li>If primary is null, returns fallback directly</li>
     *   <li>If fallback is null, returns primary directly</li>
     * </ul>
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * ExecutionConfig defaultExecConfig = ExecutionConfig.builder()
     *     .timeout(Duration.ofMinutes(5))
     *     .maxAttempts(3)
     *     .build();
     *
     * GenerateOptions defaults = GenerateOptions.builder()
     *     .temperature(0.7)
     *     .executionConfig(defaultExecConfig)
     *     .build();
     *
     * ExecutionConfig customExecConfig = ExecutionConfig.builder()
     *     .timeout(Duration.ofSeconds(30))
     *     .build();
     *
     * GenerateOptions perRequest = GenerateOptions.builder()
     *     .executionConfig(customExecConfig)
     *     .build();
     *
     * // Result: temperature=0.7, executionConfig with timeout=30s and maxAttempts=3
     * GenerateOptions merged = GenerateOptions.mergeOptions(perRequest, defaults);
     * }</pre>
     *
     * @param primary the primary options (higher priority)
     * @param fallback the fallback options (lower priority)
     * @return merged options, or null if both are null
     */
    public static GenerateOptions mergeOptions(GenerateOptions primary, GenerateOptions fallback) {
        if (primary == null) {
            return fallback;
        }
        if (fallback == null) {
            return primary;
        }

        Builder builder = builder();
        builder.temperature(
                primary.temperature != null ? primary.temperature : fallback.temperature);
        builder.topP(primary.topP != null ? primary.topP : fallback.topP);
        builder.maxTokens(primary.maxTokens != null ? primary.maxTokens : fallback.maxTokens);
        builder.frequencyPenalty(
                primary.frequencyPenalty != null
                        ? primary.frequencyPenalty
                        : fallback.frequencyPenalty);
        builder.presencePenalty(
                primary.presencePenalty != null
                        ? primary.presencePenalty
                        : fallback.presencePenalty);
        builder.thinkingBudget(
                primary.thinkingBudget != null ? primary.thinkingBudget : fallback.thinkingBudget);
        builder.executionConfig(
                ExecutionConfig.mergeConfigs(primary.executionConfig, fallback.executionConfig));

        // Merge additionalOptions: fallback first, then override with primary
        if (fallback.additionalOptions != null && !fallback.additionalOptions.isEmpty()) {
            for (Map.Entry<String, Object> entry : fallback.additionalOptions.entrySet()) {
                builder.additionalOption(entry.getKey(), entry.getValue());
            }
        }
        if (primary.additionalOptions != null && !primary.additionalOptions.isEmpty()) {
            for (Map.Entry<String, Object> entry : primary.additionalOptions.entrySet()) {
                builder.additionalOption(entry.getKey(), entry.getValue());
            }
        }

        return builder.build();
    }

    public static class Builder {
        private Double temperature;
        private Double topP;
        private Integer maxTokens;
        private Double frequencyPenalty;
        private Double presencePenalty;
        private Integer thinkingBudget;
        private ExecutionConfig executionConfig;
        private Map<String, Object> additionalOptions;

        /**
         * Sets the temperature for text generation.
         *
         * <p>Higher values (e.g., 0.8) make output more random, while lower values
         * (e.g., 0.2) make it more focused and deterministic.
         *
         * @param temperature the temperature value between 0 and 2
         * @return this builder instance
         */
        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        /**
         * Sets the top-p (nucleus sampling) parameter.
         *
         * <p>Controls diversity via nucleus sampling: considers the smallest set of tokens
         * whose cumulative probability exceeds the top_p value.
         *
         * @param topP the top-p value between 0 and 1
         * @return this builder instance
         */
        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        /**
         * Sets the maximum number of tokens to generate.
         *
         * @param maxTokens the maximum tokens limit
         * @return this builder instance
         */
        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * Sets the frequency penalty.
         *
         * <p>Reduces repetition by penalizing tokens based on their frequency in the text so far.
         * Higher values decrease repetition more strongly.
         *
         * @param frequencyPenalty the frequency penalty between -2 and 2
         * @return this builder instance
         */
        public Builder frequencyPenalty(Double frequencyPenalty) {
            this.frequencyPenalty = frequencyPenalty;
            return this;
        }

        /**
         * Sets the presence penalty.
         *
         * <p>Reduces repetition by penalizing tokens that have already appeared in the text.
         * Higher values decrease repetition more strongly.
         *
         * @param presencePenalty the presence penalty between -2 and 2
         * @return this builder instance
         */
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
         * Sets the execution configuration for timeout and retry behavior.
         *
         * <p>When configured, model API calls will apply timeout and retry logic according
         * to the execution config (timeout duration, max attempts, backoff, error filtering).
         *
         * @param executionConfig the execution configuration, or null to disable
         * @return this builder instance
         */
        public Builder executionConfig(ExecutionConfig executionConfig) {
            this.executionConfig = executionConfig;
            return this;
        }

        /**
         * Adds an additional option.
         *
         * <p>This method allows setting provider-specific options not covered by the standard options.
         *
         * @param key the option key
         * @param value the option value
         * @return this builder instance
         */
        public Builder additionalOption(String key, Object value) {
            if (this.additionalOptions == null) {
                this.additionalOptions = new HashMap<>();
            }
            this.additionalOptions.put(key, value);
            return this;
        }

        /**
         * Builds a new GenerateOptions instance with the set values.
         *
         * @return a new GenerateOptions instance
         */
        public GenerateOptions build() {
            return new GenerateOptions(this);
        }
    }
}
