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

import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.formatter.openai.dto.OpenAIStreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * OpenAI Chat Model using native HTTP API.
 *
 * <p>This implementation uses direct HTTP calls to OpenAI-compatible APIs.
 *
 * <p>Features:
 * <ul>
 *   <li>Streaming and non-streaming modes</li>
 *   <li>Tool calling support</li>
 *   <li>Automatic message format conversion</li>
 *   <li>Timeout and retry configuration</li>
 *   <li>Multi-provider support via different Formatters</li>
 * </ul>
 *
 * <p>Provider-specific behavior is handled by the Formatter. Use the appropriate formatter
 * for your provider:
 * <ul>
 *   <li>{@link OpenAIChatFormatter} - Standard OpenAI GPT models</li>
 *   <li>{@link io.agentscope.core.formatter.openai.DeepSeekFormatter} - DeepSeek Chat models</li>
 *   <li>{@link io.agentscope.core.formatter.openai.GLMFormatter} - Zhipu GLM models</li>
 * </ul>
 */
public class OpenAIChatModel extends ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(OpenAIChatModel.class);

    private final OpenAIClient client;
    private final Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter;
    private final GenerateOptions configuredOptions;

    /**
     * Creates a new OpenAI chat model instance with pre-configured options.
     *
     * @param client            the OpenAI HTTP client
     * @param formatter         the message formatter
     * @param configuredOptions the pre-configured options (can be null for stateless usage)
     */
    private OpenAIChatModel(
            OpenAIClient client,
            Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter,
            GenerateOptions configuredOptions) {
        this.client = client != null ? client : new OpenAIClient();
        this.formatter = formatter != null ? formatter : new OpenAIChatFormatter();
        this.configuredOptions = configuredOptions;
    }

    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return ModelUtils.applyTimeoutAndRetry(
                doStream0(messages, tools, options),
                options,
                configuredOptions,
                configuredOptions.getModelName(),
                "openai");
    }

    protected Flux<ChatResponse> doStream0(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {

        // Merge provided options with configured options (provided takes precedence)
        GenerateOptions effectiveOptions = GenerateOptions.mergeOptions(options, configuredOptions);

        if (effectiveOptions == null || effectiveOptions.getModelName() == null) {
            throw new IllegalArgumentException(
                    "modelName must be specified in GenerateOptions or configured in builder");
        }

        String modelName = effectiveOptions.getModelName();
        log.debug("OpenAI API call: model={}", modelName);

        // Determine streaming mode (effectiveOptions.stream takes precedence)
        boolean stream =
                effectiveOptions.getStream() != null ? effectiveOptions.getStream() : false;

        // Get apiKey and baseUrl from effectiveOptions
        String apiKey = effectiveOptions.getApiKey();
        String baseUrl = effectiveOptions.getBaseUrl();

        Instant start = Instant.now();

        // Format messages using formatter (handles provider-specific transformations)
        List<OpenAIMessage> openaiMessages = formatter.format(messages);

        // Build request
        OpenAIRequest.Builder requestBuilder =
                OpenAIRequest.builder().model(modelName).messages(openaiMessages).stream(stream);

        // Include usage in stream_options for all streaming calls
        // This ensures token usage information is available in the final response chunk
        // Required by OpenAI-compatible APIs like DashScope, Bailian, etc.
        if (stream) {
            requestBuilder.streamOptions(new OpenAIStreamOptions(true));
        }

        OpenAIRequest request = requestBuilder.build();

        // Apply tools to request (formatter handles provider-specific tool format)
        if (tools != null && !tools.isEmpty()) {
            formatter.applyTools(request, tools);
        }

        // Apply generation options (formatter handles provider-specific options)
        formatter.applyOptions(request, effectiveOptions, null);

        // Apply tool choice if specified (formatter handles provider-specific tool choice)
        if (effectiveOptions.getToolChoice() != null) {
            formatter.applyToolChoice(request, effectiveOptions.getToolChoice());
        }

        // Make the API call
        if (stream) {
            // Streaming mode
            return client.stream(apiKey, baseUrl, request, effectiveOptions)
                    .map(response -> formatter.parseResponse(response, start))
                    .filter(Objects::nonNull);
        } else {
            // Non-streaming mode: make a single call and return as Flux
            return Flux.defer(
                            () -> {
                                try {
                                    OpenAIResponse response =
                                            client.call(apiKey, baseUrl, request, effectiveOptions);
                                    ChatResponse chatResponse =
                                            formatter.parseResponse(response, start);
                                    return Flux.just(chatResponse);
                                } catch (Exception e) {
                                    return Flux.error(
                                            new ModelException(
                                                    "Failed to call OpenAI API: " + e.getMessage(),
                                                    e,
                                                    modelName,
                                                    "openai"));
                                }
                            })
                    .subscribeOn(Schedulers.boundedElastic());
        }
    }

    /**
     * Gets the model name for logging and identification.
     *
     * @return the model name, or null if not configured
     */
    @Override
    public String getModelName() {
        return configuredOptions != null ? configuredOptions.getModelName() : null;
    }

    /**
     * Creates a new builder for OpenAIChatModel.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for OpenAIChatModel.
     *
     * <p>The built model internally wraps the configuration so that calls without explicit
     * options use the builder-provided values.
     */
    public static class Builder {
        private String apiKey;
        private String modelName;
        private Boolean stream;
        private GenerateOptions defaultOptions;
        private String baseUrl;
        private String endpointPath;
        private Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter;
        private HttpTransport httpTransport;

        /**
         * Sets the API key for OpenAI authentication.
         *
         * @param apiKey the API key
         * @return this builder instance
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the model name to use.
         *
         * @param modelName the model name (e.g., "gpt-4", "gpt-3.5-turbo")
         * @return this builder instance
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * Sets whether streaming should be enabled.
         *
         * @param stream true to enable streaming, false for non-streaming
         * @return this builder instance
         */
        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        /**
         * Sets the default generation options.
         *
         * @param options the default options to use (null for defaults)
         * @return this builder instance
         */
        public Builder generateOptions(GenerateOptions options) {
            this.defaultOptions = options;
            return this;
        }

        /**
         * Sets a custom base URL for OpenAI API.
         *
         * @param baseUrl the base URL (null for default)
         * @return this builder instance
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets a custom endpoint path for the API request.
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
         * Sets the message formatter to use.
         *
         * <p>Use provider-specific formatters for different providers:
         * <ul>
         *   <li>{@link OpenAIChatFormatter} - Standard OpenAI GPT models</li>
         *   <li>{@link io.agentscope.core.formatter.openai.DeepSeekFormatter} - DeepSeek Chat models</li>
         *   <li>{@link io.agentscope.core.formatter.openai.GLMFormatter} - Zhipu GLM models</li>
         * </ul>
         *
         * @param formatter the formatter (null for default OpenAI formatter)
         * @return this builder instance
         */
        public Builder formatter(
                Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter) {
            this.formatter = formatter;
            return this;
        }

        /**
         * Sets the HTTP transport to use.
         *
         * @param httpTransport the HTTP transport (null for default from factory)
         * @return this builder instance
         */
        public Builder httpTransport(HttpTransport httpTransport) {
            this.httpTransport = httpTransport;
            return this;
        }

        /**
         * Builds the OpenAIChatModel instance.
         *
         * @return configured OpenAIChatModel instance
         * @throws IllegalArgumentException if modelName is not set
         */
        public OpenAIChatModel build() {
            Objects.requireNonNull(modelName, "modelName must be set");

            // Build options from builder fields (these take precedence)
            GenerateOptions.Builder optionsBuilder =
                    GenerateOptions.builder()
                            .apiKey(apiKey)
                            .baseUrl(baseUrl)
                            .modelName(modelName)
                            .stream(stream);

            if (endpointPath != null) {
                optionsBuilder.endpointPath(endpointPath);
            }

            GenerateOptions builderOptions = optionsBuilder.build();

            // Merge with defaultOptions (builder fields take precedence)
            GenerateOptions mergedOptions =
                    GenerateOptions.mergeOptions(builderOptions, defaultOptions);

            // Ensure execution config has defaults
            GenerateOptions effectiveOptions =
                    ModelUtils.ensureDefaultExecutionConfig(mergedOptions);

            // Create transport
            HttpTransport transport =
                    httpTransport != null ? httpTransport : HttpTransportFactory.getDefault();
            OpenAIClient client = new OpenAIClient(transport);
            Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> fmt =
                    formatter != null ? formatter : new OpenAIChatFormatter();

            return new OpenAIChatModel(client, fmt, effectiveOptions);
        }
    }
}
