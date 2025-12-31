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
package io.agentscope.core.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration context for OpenAI-compatible API calls.
 *
 * <p>This class holds the configuration that is typically set once and reused across multiple
 * model invocations, such as API key, base URL, and default streaming mode.
 *
 * <p>Unlike {@link GenerateOptions} which holds per-request generation parameters, this class
 * holds connection-level configuration that applies to all requests using this config.
 *
 * <p><b>Example usage:</b>
 * <pre>{@code
 * // Configure once
 * OpenAIConfig config = OpenAIConfig.builder()
 *     .apiKey("sk-xxx")
 *     .baseUrl("https://api.openai.com/v1")
 *     .stream(true)
 *     .build();
 *
 * // Reuse with different models
 * GenerateOptions opts1 = config.toOptions().modelName("gpt-4").build();
 * GenerateOptions opts2 = config.toOptions().modelName("gpt-3.5-turbo").build();
 *
 * model.stream(messages1, null, opts1);
 * model.stream(messages2, null, opts2);
 * }</pre>
 */
public class OpenAIConfig {

    /** Default base URL for OpenAI API. */
    public static final String DEFAULT_BASE_URL = "https://api.openai.com";

    /** Default base URL with version. */
    public static final String DEFAULT_BASE_URL_WITH_VERSION = DEFAULT_BASE_URL + "/v1";

    private final String apiKey;
    private final String baseUrl;
    private final Boolean stream;
    private final Map<String, String> additionalHeaders;
    private final Map<String, String> additionalQueryParams;

    private OpenAIConfig(Builder builder) {
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl != null ? builder.baseUrl : DEFAULT_BASE_URL_WITH_VERSION;
        this.stream = builder.stream;
        this.additionalHeaders =
                builder.additionalHeaders != null
                        ? Collections.unmodifiableMap(new HashMap<>(builder.additionalHeaders))
                        : Collections.emptyMap();
        this.additionalQueryParams =
                builder.additionalQueryParams != null
                        ? Collections.unmodifiableMap(new HashMap<>(builder.additionalQueryParams))
                        : Collections.emptyMap();
    }

    /**
     * Gets the API key for authentication.
     *
     * @return the API key, or null if not set
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * Gets the base URL for the API endpoint.
     *
     * @return the base URL
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Gets whether streaming mode is enabled by default.
     *
     * @return true for streaming, false for non-streaming, null if not specified
     */
    public Boolean getStream() {
        return stream;
    }

    /**
     * Gets the additional HTTP headers to include in API requests.
     *
     * @return an unmodifiable map of additional headers
     */
    public Map<String, String> getAdditionalHeaders() {
        return additionalHeaders;
    }

    /**
     * Gets the additional query parameters to include in API requests.
     *
     * @return an unmodifiable map of additional query parameters
     */
    public Map<String, String> getAdditionalQueryParams() {
        return additionalQueryParams;
    }

    /**
     * Creates a new GenerateOptions.Builder with this config's values pre-populated.
     *
     * <p>This provides a convenient way to create request-specific options while reusing the
     * common configuration:
     * <pre>{@code
     * GenerateOptions opts = config.toOptions()
     *     .modelName("gpt-4")
     *     .maxTokens(1000)
     *     .build();
     * }</pre>
     *
     * @return a GenerateOptions.Builder with this config's values
     */
    public GenerateOptions.Builder toOptions() {
        GenerateOptions.Builder builder = GenerateOptions.builder();
        if (additionalHeaders != null && !additionalHeaders.isEmpty()) {
            builder.additionalHeaders(additionalHeaders);
        }
        if (additionalQueryParams != null && !additionalQueryParams.isEmpty()) {
            builder.additionalQueryParams(additionalQueryParams);
        }
        return builder;
    }

    /**
     * Creates a GenerateOptions with the specified model name, using this config's values.
     *
     * <p>This is a shortcut for:
     * <pre>{@code
     * GenerateOptions opts = config.toOptions()
     *     .modelName(modelName)
     *     .build();
     * }</pre>
     *
     * @param modelName the model name to use
     * @return a GenerateOptions with the model name and this config's values
     */
    public GenerateOptions withModel(String modelName) {
        return toOptions().modelName(modelName).build();
    }

    /**
     * Creates a new builder for OpenAIConfig.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for OpenAIConfig.
     */
    public static class Builder {
        private String apiKey;
        private String baseUrl;
        private Boolean stream;
        private Map<String, String> additionalHeaders;
        private Map<String, String> additionalQueryParams;

        /**
         * Sets the API key for OpenAI authentication.
         *
         * @param apiKey the API key
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the base URL for the API endpoint.
         *
         * @param baseUrl the base URL (null for default OpenAI API)
         * @return this builder
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets whether streaming mode should be enabled.
         *
         * @param stream true for streaming, false for non-streaming
         * @return this builder
         */
        public Builder stream(Boolean stream) {
            this.stream = stream;
            return this;
        }

        /**
         * Adds an additional HTTP header to include in API requests.
         *
         * @param key the header name
         * @param value the header value
         * @return this builder
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
         * @return this builder
         */
        public Builder additionalHeaders(Map<String, String> headers) {
            this.additionalHeaders = headers != null ? new HashMap<>(headers) : null;
            return this;
        }

        /**
         * Adds an additional query parameter to include in API requests.
         *
         * @param key the parameter name
         * @param value the parameter value
         * @return this builder
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
         * @return this builder
         */
        public Builder additionalQueryParams(Map<String, String> params) {
            this.additionalQueryParams = params != null ? new HashMap<>(params) : null;
            return this;
        }

        /**
         * Builds the OpenAIConfig instance.
         *
         * @return a new OpenAIConfig instance
         */
        public OpenAIConfig build() {
            return new OpenAIConfig(this);
        }
    }
}
