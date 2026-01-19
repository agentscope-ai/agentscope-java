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

package io.agentscope.core.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable generation options for LLM models.
 * Use the builder pattern to construct instances.
 *
 * <p>This class holds both per-request generation parameters (temperature, maxTokens, etc.)
 * and connection-level configuration (apiKey, baseUrl, modelName, stream).
 */
public class GenerateOptions {
    // Connection-level configuration
    private final String apiKey;
    private final String baseUrl;
    private final String endpointPath;
    private final String modelName;
    private final Boolean stream;

    // Generation parameters
    private final Double temperature;
    private final Double topP;
    private final Integer maxTokens;
    private final Double frequencyPenalty;
    private final Double presencePenalty;
    private final Integer thinkingBudget;
    private final String reasoningEffort;
    private final ExecutionConfig executionConfig;
    private final ToolChoice toolChoice;
    private final Integer topK;
    private final Long seed;
    private final Map<String, String> additionalHeaders;
    private final Map<String, Object> additionalBodyParams;
    private final Map<String, String> additionalQueryParams;

    /**
     * Creates a new GenerateOptions instance using the builder pattern.
     *
     * @param builder the builder containing the generation options configuration
     */
    private GenerateOptions(Builder builder) {
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.endpointPath = builder.endpointPath;
        this.modelName = builder.modelName;
        this.stream = builder.stream;
        this.temperature = builder.temperature;
        this.topP = builder.topP;
        this.maxTokens = builder.maxTokens;
        this.frequencyPenalty = builder.frequencyPenalty;
        this.presencePenalty = builder.presencePenalty;
        this.thinkingBudget = builder.thinkingBudget;
        this.reasoningEffort = builder.reasoningEffort;
        this.executionConfig = builder.executionConfig;
        this.toolChoice = builder.toolChoice;
        this.topK = builder.topK;
        this.seed = builder.seed;
        this.additionalHeaders =
                builder.additionalHeaders != null
                        ? Collections.unmodifiableMap(new HashMap<>(builder.additionalHeaders))
                        : Collections.emptyMap();
        this.additionalBodyParams =
                builder.additionalBodyParams != null
                        ? Collections.unmodifiableMap(new HashMap<>(builder.additionalBodyParams))
                        : Collections.emptyMap();
        this.additionalQueryParams =
                builder.additionalQueryParams != null
                        ? Collections.unmodifiableMap(new HashMap<>(builder.additionalQueryParams))
                        : Collections.emptyMap();
    }

    /**
     * Gets the API key for authentication.
     *
     * <p>This is the API key used to authenticate with the LLM provider.
     * When null, the model's default API key will be used (if configured).
     *
     * @return the API key, or null if not set
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * Gets the base URL for the API endpoint.
     *
     * <p>This is the base URL of the LLM provider's API.
     * When null, the model's default base URL will be used (if configured).
     *
     * @return the base URL, or null if not set
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Gets the endpoint path for the API request.
     *
     * <p>This is the API endpoint path (e.g., "/v1/chat/completions").
     * When null, the model's default endpoint path will be used.
     *
     * <p>This allows customization for OpenAI-compatible APIs that use different
     * endpoint paths than the standard OpenAI API.
     *
     * @return the endpoint path, or null if not set
     */
    public String getEndpointPath() {
        return endpointPath;
    }

    /**
     * Gets the model name to use for generation.
     *
     * <p>This specifies which model to use (e.g., "gpt-4", "gpt-3.5-turbo").
     * When null, the model's default model name will be used (if configured).
     *
     * @return the model name, or null if not set
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * Gets whether streaming mode is enabled.
     *
     * <p>When true, responses will be streamed as they are generated.
     * When false, the full response will be returned when complete.
     * When null, the model's default streaming mode will be used (if configured).
     *
     * @return true for streaming, false for non-streaming, null if not set
     */
    public Boolean getStream() {
        return stream;
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
     * Gets the reasoning effort level for o1 models.
     *
     * <p>This parameter controls how much effort the model spends on reasoning.
     * Valid values are "low", "medium", and "high".
     *
     * @return the reasoning effort level, or null if not set
     */
    public String getReasoningEffort() {
        return reasoningEffort;
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
     * Gets the tool choice configuration for controlling how the model uses tools.
     *
     * <p>When set, this controls whether the model can call tools, must call tools,
     * or must call a specific tool. When null, the default behavior (auto) is used.
     *
     * @return the tool choice configuration, or null if not set (defaults to auto)
     * @see ToolChoice
     */
    public ToolChoice getToolChoice() {
        return toolChoice;
    }

    /**
     * Gets the top-k sampling parameter.
     *
     * <p>Limits the model to only consider the top K most probable tokens at each step.
     * Lower values make output more focused, higher values allow more diversity.
     *
     * @return the top-k value, or null if not set
     */
    public Integer getTopK() {
        return topK;
    }

    /**
     * Gets the random seed for deterministic generation.
     *
     * <p>When set, the model will attempt to generate the same output for the same
     * input and seed value, enabling reproducible results.
     *
     * @return the seed value, or null if not set
     */
    public Long getSeed() {
        return seed;
    }

    /**
     * Gets the additional HTTP headers to include in API requests.
     *
     * <p>These headers will be merged with the default headers when making API calls.
     * Useful for passing custom authentication, tracing, or provider-specific headers.
     *
     * @return an unmodifiable map of additional headers, empty if none set
     */
    public Map<String, String> getAdditionalHeaders() {
        return additionalHeaders;
    }

    /**
     * Gets the additional parameters to include in the request body.
     *
     * <p>These parameters will be merged into the API request body, allowing
     * provider-specific options not covered by the standard fields.
     *
     * @return an unmodifiable map of additional body parameters, empty if none set
     */
    public Map<String, Object> getAdditionalBodyParams() {
        return additionalBodyParams;
    }

    /**
     * Gets the additional query parameters to include in API requests.
     *
     * <p>These parameters will be appended to the API request URL as query string.
     *
     * @return an unmodifiable map of additional query parameters, empty if none set
     */
    public Map<String, String> getAdditionalQueryParams() {
        return additionalQueryParams;
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
     *   <li>Map fields (additionalHeaders, etc.): merges both maps, with primary values overriding fallback</li>
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
        builder.apiKey(primary.apiKey != null ? primary.apiKey : fallback.apiKey);
        builder.baseUrl(primary.baseUrl != null ? primary.baseUrl : fallback.baseUrl);
        builder.endpointPath(
                primary.endpointPath != null ? primary.endpointPath : fallback.endpointPath);
        builder.modelName(primary.modelName != null ? primary.modelName : fallback.modelName);
        builder.stream(primary.stream != null ? primary.stream : fallback.stream);
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
        builder.reasoningEffort(
                primary.reasoningEffort != null
                        ? primary.reasoningEffort
                        : fallback.reasoningEffort);
        builder.executionConfig(
                ExecutionConfig.mergeConfigs(primary.executionConfig, fallback.executionConfig));
        builder.toolChoice(primary.toolChoice != null ? primary.toolChoice : fallback.toolChoice);
        builder.topK(primary.topK != null ? primary.topK : fallback.topK);
        builder.seed(primary.seed != null ? primary.seed : fallback.seed);

        // Merge map fields: fallback first, then override with primary
        mergeMaps(fallback.additionalHeaders, primary.additionalHeaders, builder::additionalHeader);
        mergeMaps(
                fallback.additionalBodyParams,
                primary.additionalBodyParams,
                builder::additionalBodyParam);
        mergeMaps(
                fallback.additionalQueryParams,
                primary.additionalQueryParams,
                builder::additionalQueryParam);

        return builder.build();
    }

    private static <V> void mergeMaps(
            Map<String, V> fallback,
            Map<String, V> primary,
            java.util.function.BiConsumer<String, V> adder) {
        if (fallback != null && !fallback.isEmpty()) {
            for (Map.Entry<String, V> entry : fallback.entrySet()) {
                adder.accept(entry.getKey(), entry.getValue());
            }
        }
        if (primary != null && !primary.isEmpty()) {
            for (Map.Entry<String, V> entry : primary.entrySet()) {
                adder.accept(entry.getKey(), entry.getValue());
            }
        }
    }

    public static class Builder {
        // Connection-level configuration
        private String apiKey;
        private String baseUrl;
        private String endpointPath;
        private String modelName;
        private Boolean stream;

        // Generation parameters
        private Double temperature;
        private Double topP;
        private Integer maxTokens;
        private Double frequencyPenalty;
        private Double presencePenalty;
        private Integer thinkingBudget;
        private String reasoningEffort;
        private ExecutionConfig executionConfig;
        private ToolChoice toolChoice;
        private Integer topK;
        private Long seed;
        private Map<String, String> additionalHeaders;
        private Map<String, Object> additionalBodyParams;
        private Map<String, String> additionalQueryParams;

        /**
         * Sets the API key for authentication.
         *
         * @param apiKey the API key
         * @return this builder instance
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the base URL for the API endpoint.
         *
         * @param baseUrl the base URL
         * @return this builder instance
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the endpoint path for the API request.
         *
         * <p>This allows customization for OpenAI-compatible APIs that use different
         * endpoint paths than the standard OpenAI API (e.g., "/v4/chat/completions",
         * "/api/v1/llm/chat", etc.). When null, the default endpoint path will be used.
         *
         * @param endpointPath the endpoint path (e.g., "/v1/chat/completions")
         * @return this builder instance
         */
        public Builder endpointPath(String endpointPath) {
            this.endpointPath = endpointPath;
            return this;
        }

        /**
         * Sets the model name to use for generation.
         *
         * @param modelName the model name (e.g., "gpt-4", "gpt-3.5-turbo")
         * @return this builder instance
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * Sets whether streaming mode is enabled.
         *
         * @param stream true for streaming, false for non-streaming
         * @return this builder instance
         */
        public Builder stream(Boolean stream) {
            this.stream = stream;
            return this;
        }

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
         * Sets the reasoning effort level for o1 models.
         *
         * <p>This parameter controls how much effort the model spends on reasoning.
         * Valid values are "low", "medium", and "high".
         *
         * @param reasoningEffort the reasoning effort level
         * @return this builder
         */
        public Builder reasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
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
         * Sets the tool choice configuration for controlling how the model uses tools.
         *
         * <p>This setting controls whether the model can call tools, must call tools,
         * or must call a specific tool:
         * <ul>
         *   <li>{@link ToolChoice.Auto} - Let model decide (default when null)</li>
         *   <li>{@link ToolChoice.None} - Prevent tool calling</li>
         *   <li>{@link ToolChoice.Required} - Force at least one tool call</li>
         *   <li>{@link ToolChoice.Specific} - Force specific tool call</li>
         * </ul>
         *
         * @param toolChoice the tool choice configuration, or null for default (auto)
         * @return this builder instance
         * @see ToolChoice
         */
        public Builder toolChoice(ToolChoice toolChoice) {
            this.toolChoice = toolChoice;
            return this;
        }

        /**
         * Sets the top-k sampling parameter.
         *
         * <p>Limits the model to only consider the top K most probable tokens at each step.
         * Lower values make output more focused, higher values allow more diversity.
         *
         * @param topK the top-k value
         * @return this builder instance
         */
        public Builder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

        /**
         * Sets the random seed for deterministic generation.
         *
         * <p>When set, the model will attempt to generate the same output for the same
         * input and seed value, enabling reproducible results.
         *
         * @param seed the seed value
         * @return this builder instance
         */
        public Builder seed(Long seed) {
            this.seed = seed;
            return this;
        }

        /**
         * Adds an additional HTTP header to include in API requests.
         *
         * @param key the header name
         * @param value the header value
         * @return this builder instance
         */
        public Builder additionalHeader(String key, String value) {
            if (this.additionalHeaders == null) {
                this.additionalHeaders = new HashMap<>();
            }
            this.additionalHeaders.put(key, value);
            return this;
        }

        /**
         * Sets all additional HTTP headers to include in API requests.
         *
         * @param headers the headers map
         * @return this builder instance
         */
        public Builder additionalHeaders(Map<String, String> headers) {
            this.additionalHeaders = headers != null ? new HashMap<>(headers) : null;
            return this;
        }

        /**
         * Adds an additional parameter to include in the request body.
         *
         * @param key the parameter name
         * @param value the parameter value
         * @return this builder instance
         */
        public Builder additionalBodyParam(String key, Object value) {
            if (this.additionalBodyParams == null) {
                this.additionalBodyParams = new HashMap<>();
            }
            this.additionalBodyParams.put(key, value);
            return this;
        }

        /**
         * Sets all additional parameters to include in the request body.
         *
         * @param params the parameters map
         * @return this builder instance
         */
        public Builder additionalBodyParams(Map<String, Object> params) {
            this.additionalBodyParams = params != null ? new HashMap<>(params) : null;
            return this;
        }

        /**
         * Adds an additional query parameter to include in API requests.
         *
         * @param key the parameter name
         * @param value the parameter value
         * @return this builder instance
         */
        public Builder additionalQueryParam(String key, String value) {
            if (this.additionalQueryParams == null) {
                this.additionalQueryParams = new HashMap<>();
            }
            this.additionalQueryParams.put(key, value);
            return this;
        }

        /**
         * Sets all additional query parameters to include in API requests.
         *
         * @param params the parameters map
         * @return this builder instance
         */
        public Builder additionalQueryParams(Map<String, String> params) {
            this.additionalQueryParams = params != null ? new HashMap<>(params) : null;
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
