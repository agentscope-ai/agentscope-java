/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.model;

import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import java.io.Closeable;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * OpenAI Chat Model using native HTTP API.
 *
 * <p>This implementation uses direct HTTP calls to OpenAI API via OkHttp,
 * without depending on the OpenAI Java SDK.
 *
 * <p>Features:
 * <ul>
 *   <li>Streaming and non-streaming modes</li>
 *   <li>Tool calling support</li>
 *   <li>Automatic message format conversion</li>
 *   <li>Timeout and retry configuration</li>
 * </ul>
 */
public class OpenAIChatModel extends ChatModelBase implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(OpenAIChatModel.class);

    private final String modelName;
    private final boolean stream;
    private final GenerateOptions defaultOptions;
    private final Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter;

    // HTTP client for API calls
    private final OpenAIClient httpClient;

    /**
     * Creates a new OpenAI chat model instance.
     *
     * @param apiKey the API key for OpenAI authentication
     * @param modelName the model name (e.g., "gpt-4", "gpt-3.5-turbo")
     * @param stream whether streaming should be enabled
     * @param defaultOptions default generation options (null for defaults)
     * @param baseUrl custom base URL for OpenAI API (null for default)
     * @param formatter the message formatter to use (null for default OpenAI formatter)
     * @param httpTransport custom HTTP transport (null for default from factory)
     */
    public OpenAIChatModel(
            String apiKey,
            String modelName,
            boolean stream,
            GenerateOptions defaultOptions,
            String baseUrl,
            Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter,
            HttpTransport httpTransport) {
        Objects.requireNonNull(apiKey, "apiKey cannot be null");
        Objects.requireNonNull(modelName, "modelName cannot be null");

        this.modelName = modelName;
        this.stream = stream;
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : GenerateOptions.builder().build();
        this.formatter = formatter != null ? formatter : new OpenAIChatFormatter();

        // Initialize HTTP client with provided transport or factory default
        HttpTransport transport =
                httpTransport != null ? httpTransport : HttpTransportFactory.getDefault();
        this.httpClient =
                OpenAIClient.builder().transport(transport).apiKey(apiKey).baseUrl(baseUrl).build();
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
     * Stream chat completion responses from OpenAI's API.
     *
     * <p>Supports timeout and retry configuration through GenerateOptions.
     *
     * @param messages AgentScope messages to send to the model
     * @param tools Optional list of tool schemas
     * @param options Optional generation options (null to use defaults)
     * @return Flux stream of chat responses
     */
    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {

        log.debug("OpenAI API call: model={}", modelName);

        Flux<ChatResponse> responseFlux = streamWithHttpClient(messages, tools, options);

        // Apply timeout and retry if configured
        return ModelUtils.applyTimeoutAndRetry(
                responseFlux, options, defaultOptions, modelName, "openai", log);
    }

    /**
     * Stream using HTTP client.
     *
     * <p>This method uses the native OpenAI HTTP API directly via OkHttp.
     */
    private Flux<ChatResponse> streamWithHttpClient(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Instant start = Instant.now();

        // Get effective options
        GenerateOptions effectiveOptions = options != null ? options : defaultOptions;
        ToolChoice toolChoice = effectiveOptions.getToolChoice();

        // Format messages using formatter
        List<OpenAIMessage> openaiMessages = formatter.format(messages);

        // Build request using formatter
        OpenAIRequest request =
                OpenAIRequest.builder().model(modelName).messages(openaiMessages).stream(stream)
                        .build();

        formatter.applyOptions(request, options, defaultOptions);
        formatter.applyTools(request, tools);
        formatter.applyToolChoice(request, toolChoice);

        if (stream) {
            // Streaming mode
            return httpClient.stream(request, options)
                    .map(response -> formatter.parseResponse(response, start));
        } else {
            // Non-streaming mode
            return Flux.defer(
                    () -> {
                        try {
                            // Ensure options are passed to httpClient.call
                            GenerateOptions effectiveOpts =
                                    options != null ? options : defaultOptions;
                            OpenAIResponse response = httpClient.call(request, effectiveOpts);
                            ChatResponse chatResponse = formatter.parseResponse(response, start);
                            return Flux.just(chatResponse);
                        } catch (Exception e) {
                            log.error("OpenAI HTTP client error: {}", e.getMessage(), e);
                            return Flux.error(
                                    new RuntimeException(
                                            "OpenAI API call failed: " + e.getMessage(), e));
                        }
                    });
        }
    }

    /**
     * Gets the model name for logging and identification.
     *
     * @return the model name
     */
    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * Gets the base URL for the OpenAI API.
     *
     * @return the base URL
     */
    public String getBaseUrl() {
        return httpClient.getBaseUrl();
    }

    public static class Builder {
        private String apiKey;
        private String modelName;
        private boolean stream = true;
        private GenerateOptions defaultOptions = null;
        private String baseUrl;
        private Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter;
        private HttpTransport httpTransport;
        private String reasoningEffort;

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
        public Builder defaultOptions(GenerateOptions options) {
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
         * Sets the message formatter to use.
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
         * Sets the reasoning effort for o1 models.
         *
         * <p>This is a convenience method that creates or modifies the defaultOptions
         * to include the reasoning effort setting.
         *
         * @param reasoningEffort the reasoning effort ("low", "medium", "high")
         * @return this builder instance
         */
        public Builder reasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
            return this;
        }

        /**
         * Sets the HTTP transport to use.
         *
         * <p>If not set, the default transport from {@link HttpTransportFactory} will be used.
         * This allows sharing a single transport instance across multiple models for better
         * resource management.
         *
         * <p>Example:
         * <pre>{@code
         * HttpTransport custom = OkHttpTransport.builder()
         *     .config(HttpTransportConfig.builder()
         *         .connectTimeout(Duration.ofSeconds(30))
         *         .build())
         *     .build();
         *
         * OpenAIChatModel model = OpenAIChatModel.builder()
         *     .apiKey("xxx")
         *     .modelName("gpt-4")
         *     .httpTransport(custom)
         *     .build();
         * }</pre>
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
         * <p>This method ensures that the defaultOptions always has proper executionConfig
         * applied.
         *
         * @return configured OpenAIChatModel instance
         */
        public OpenAIChatModel build() {
            GenerateOptions effectiveOptions =
                    ModelUtils.ensureDefaultExecutionConfig(defaultOptions);

            // Apply reasoning effort if set
            if (reasoningEffort != null) {
                effectiveOptions =
                        GenerateOptions.builder()
                                .temperature(effectiveOptions.getTemperature())
                                .topP(effectiveOptions.getTopP())
                                .maxTokens(effectiveOptions.getMaxTokens())
                                .frequencyPenalty(effectiveOptions.getFrequencyPenalty())
                                .presencePenalty(effectiveOptions.getPresencePenalty())
                                .thinkingBudget(effectiveOptions.getThinkingBudget())
                                .executionConfig(effectiveOptions.getExecutionConfig())
                                .toolChoice(effectiveOptions.getToolChoice())
                                .topK(effectiveOptions.getTopK())
                                .seed(effectiveOptions.getSeed())
                                .additionalHeaders(effectiveOptions.getAdditionalHeaders())
                                .additionalBodyParams(effectiveOptions.getAdditionalBodyParams())
                                .additionalQueryParams(effectiveOptions.getAdditionalQueryParams())
                                .additionalBodyParam("reasoning_effort", reasoningEffort)
                                .build();
            }

            return new OpenAIChatModel(
                    apiKey, modelName, stream, effectiveOptions, baseUrl, formatter, httpTransport);
        }
    }

    /**
     * Closes the HTTP client and releases associated resources.
     * Should be called when the model is no longer needed.
     *
     * @throws IOException if an I/O error occurs
     */
    @Override
    public void close() throws IOException {
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (IOException e) {
                // Re-throw IOException as-is
                log.error("Error closing OpenAI HTTP client", e);
                throw e;
            } catch (Exception e) {
                // Wrap other exceptions as IOException
                log.error("Unexpected error closing OpenAI HTTP client", e);
                throw new IOException("Failed to close HTTP client", e);
            }
        }
    }
}
