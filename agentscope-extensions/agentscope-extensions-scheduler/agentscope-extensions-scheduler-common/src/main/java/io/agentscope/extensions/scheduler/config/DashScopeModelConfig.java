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
package io.agentscope.extensions.scheduler.config;

import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import java.util.Objects;

/**
 * Configuration for DashScope model settings.
 *
 * <p>This configuration class encapsulates all the settings for DashScope chat models.
 * It mirrors the builder pattern of {@code DashScopeChatModel.Builder} to provide
 * consistent configuration across the system.
 *
 * <p><b>Supported Features:</b>
 * <ul>
 *   <li>All DashScope model types (text and vision models)</li>
 *   <li>Configurable streaming and thinking mode</li>
 *   <li>Custom generation options and protocol settings</li>
 *   <li>Custom base URL for API endpoints</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Basic configuration
 * DashScopeModelConfig config = DashScopeModelConfig.builder()
 *     .apiKey(System.getenv("DASHSCOPE_API_KEY"))
 *     .modelName("qwen-max")
 *     .stream(true)
 *     .build();
 *
 * // With thinking mode
 * DashScopeModelConfig thinkingConfig = DashScopeModelConfig.builder()
 *     .apiKey(apiKey)
 *     .modelName("qwen-max")
 *     .enableThinking(true)
 *     .defaultOptions(GenerateOptions.builder()
 *         .thinkingBudget(5000)
 *         .build())
 *     .build();
 *
 * // Use in AgentConfig
 * AgentConfig agentConfig = AgentConfig.builder()
 *     .name("MyAgent")
 *     .modelConfig(config)
 *     .build();
 * }</pre>
 *
 * @see ModelConfig
 * @see AgentConfig
 */
public class DashScopeModelConfig implements ModelConfig {

    private final String apiKey;
    private final String modelName;
    private final boolean stream;
    private final boolean enableThinking;
    private final String baseUrl;

    private DashScopeModelConfig(Builder builder) {
        this.apiKey = builder.apiKey;
        this.modelName = builder.modelName;
        this.stream = builder.stream;
        this.enableThinking = builder.enableThinking;
        this.baseUrl = builder.baseUrl;

        validate();
    }

    /**
     * Validate the configuration.
     */
    private void validate() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("API key must not be null or empty");
        }
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new IllegalArgumentException("Model name must not be null or empty");
        }
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * Create a Model instance from this configuration.
     *
     * <p>This method creates a {@link DashScopeChatModel} instance with all the
     * configured settings. The created model is ready to use for agent execution.
     *
     * @return A configured DashScopeChatModel instance
     * @throws IllegalStateException if the model cannot be created
     */
    @Override
    public Model createModel() {
        DashScopeChatModel.Builder builder =
                DashScopeChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .formatter(new DashScopeChatFormatter())
                        .stream(stream);

        if (enableThinking) {
            builder.enableThinking(enableThinking);
        }

        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }

        return builder.build();
    }

    /**
     * Get the API key.
     *
     * @return The API key
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * Check if streaming is enabled.
     *
     * @return true if streaming is enabled
     */
    public boolean isStream() {
        return stream;
    }

    /**
     * Check if thinking mode is enabled.
     *
     * @return true if thinking mode is enabled, false if disabled
     */
    public boolean getEnableThinking() {
        return enableThinking;
    }

    /**
     * Get the base URL.
     *
     * @return The custom base URL, may be null for default
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Create a new builder instance.
     *
     * @return A new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DashScopeModelConfig that = (DashScopeModelConfig) o;
        return stream == that.stream
                && Objects.equals(apiKey, that.apiKey)
                && Objects.equals(modelName, that.modelName)
                && Objects.equals(enableThinking, that.enableThinking)
                && Objects.equals(baseUrl, that.baseUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(apiKey, modelName, stream, enableThinking, baseUrl);
    }

    @Override
    public String toString() {
        return "DashScopeModelConfig{"
                + "modelName='"
                + modelName
                + '\''
                + ", stream="
                + stream
                + ", enableThinking="
                + enableThinking
                + ", baseUrl='"
                + baseUrl
                + '\''
                + '}';
    }

    /**
     * Builder for creating DashScopeModelConfig instances.
     */
    public static class Builder {
        private String apiKey;
        private String modelName;
        private boolean stream = true;
        private boolean enableThinking;
        private String baseUrl;

        private Builder() {}

        /**
         * Set the API key for DashScope authentication (required).
         *
         * @param apiKey The API key
         * @return This builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Set the model name to use (required).
         *
         * <p>The model name determines which API is used:
         * <ul>
         *   <li>Vision models (qvq* or *-vl*) → MultiModalConversation API</li>
         *   <li>Text models → Generation API</li>
         * </ul>
         *
         * <p>Common model names:
         * <ul>
         *   <li>"qwen-max" - Latest flagship model</li>
         *   <li>"qwen-plus" - Enhanced performance</li>
         *   <li>"qwen-turbo" - Fast and efficient</li>
         *   <li>"qwen-vl-plus" - Vision-language model</li>
         * </ul>
         *
         * @param modelName The model name
         * @return This builder
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * Set whether streaming should be enabled (optional, default: true).
         *
         * <p>This setting is ignored if enableThinking is set to true, as thinking mode
         * automatically enables streaming.
         *
         * @param stream true to enable streaming, false for non-streaming
         * @return This builder
         */
        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        /**
         * Set whether thinking mode should be enabled (optional, default: false).
         *
         * <p>When enabled, this automatically enables streaming and may override the stream setting.
         * Thinking mode allows the model to show its reasoning process.
         *
         * @param enableThinking true to enable thinking mode, false to disable
         * @return This builder
         */
        public Builder enableThinking(boolean enableThinking) {
            this.enableThinking = enableThinking;
            return this;
        }

        /**
         * Set a custom base URL for DashScope API (optional).
         *
         * <p>Useful for:
         * <ul>
         *   <li>Using regional endpoints</li>
         *   <li>Proxying through custom servers</li>
         *   <li>Testing with mock servers</li>
         * </ul>
         *
         * @param baseUrl The base URL (null for default)
         * @return This builder
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Build the DashScopeModelConfig instance.
         *
         * @return A new DashScopeModelConfig instance
         * @throws IllegalArgumentException if required fields are missing or invalid
         */
        public DashScopeModelConfig build() {
            return new DashScopeModelConfig(this);
        }
    }
}
