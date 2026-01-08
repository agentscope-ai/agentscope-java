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

import io.agentscope.core.formatter.anthropic.AnthropicBaseFormatter;
import io.agentscope.core.formatter.anthropic.AnthropicChatFormatter;
import io.agentscope.core.formatter.anthropic.AnthropicResponseParser;
import io.agentscope.core.formatter.anthropic.dto.AnthropicMessage;
import io.agentscope.core.formatter.anthropic.dto.AnthropicRequest;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Anthropic Chat Model implementation using native HTTP client via OkHttp.
 *
 * <p>
 * This implementation provides complete integration with Anthropic's Messages
 * API, including
 * tool calling, streaming support, and extended thinking features.
 *
 * <p>
 * Important notes:
 *
 * <ul>
 * <li>System messages are handled via the system parameter, not as messages
 * <li>Tool results must be in separate user messages
 * <li>Supports Claude models (claude-3-*, claude-sonnet-*, etc.)
 * </ul>
 */
public class AnthropicChatModel extends ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(AnthropicChatModel.class);

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final boolean streamEnabled;
    private final AnthropicClient client;
    private final GenerateOptions defaultOptions;
    private final AnthropicBaseFormatter formatter;

    /**
     * Creates a new Anthropic chat model instance.
     *
     * @param baseUrl        the base URL for Anthropic API (null for default)
     * @param apiKey         the API key for authentication (null to load from
     *                       ANTHROPIC_API_KEY env var)
     * @param modelName      the model name to use (e.g.,
     *                       "claude-sonnet-4-5-20250929")
     * @param streamEnabled  whether streaming should be enabled
     * @param defaultOptions default generation options
     * @param formatter      the message formatter to use (null for default
     *                       Anthropic formatter)
     * @param httpTransport  the HTTP transport to use (null for default)
     */
    public AnthropicChatModel(
            String baseUrl,
            String apiKey,
            String modelName,
            boolean streamEnabled,
            GenerateOptions defaultOptions,
            AnthropicBaseFormatter formatter,
            HttpTransport httpTransport) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.streamEnabled = streamEnabled;
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : GenerateOptions.builder().build();
        this.formatter = formatter != null ? formatter : new AnthropicChatFormatter();

        // Initialize Anthropic client
        HttpTransport transport =
                httpTransport != null ? httpTransport : HttpTransportFactory.getDefault();
        this.client = new AnthropicClient(transport);
    }

    /**
     * Stream chat completion responses from Anthropic's API.
     *
     * <p>
     * This method internally handles message formatting using the configured
     * formatter. It
     * supports both streaming and non-streaming modes based on the streamEnabled
     * setting.
     *
     * <p>
     * Supports timeout and retry configuration through GenerateOptions.
     *
     * @param messages AgentScope messages to send to the model
     * @param tools    Optional list of tool schemas (null or empty if no tools)
     * @param options  Optional generation options (null to use defaults)
     * @return Flux stream of chat responses
     */
    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Instant startTime = Instant.now();
        log.debug(
                "Anthropic stream: model={}, messages={}, tools_present={}",
                modelName,
                messages != null ? messages.size() : 0,
                tools != null && !tools.isEmpty());

        Flux<ChatResponse> responseFlux =
                Flux.defer(
                        () -> {
                            try {
                                // Build Anthropic request
                                AnthropicRequest request = new AnthropicRequest();
                                request.setModel(modelName);
                                request.setMaxTokens(4096);

                                // Extract and apply system message (Anthropic-specific requirement)
                                formatter.applySystemMessage(request, messages);

                                // Use formatter to convert Msg to Anthropic messages
                                List<AnthropicMessage> formattedMessages =
                                        formatter.format(messages);
                                request.setMessages(formattedMessages);

                                // Apply generation options via formatter
                                formatter.applyOptions(request, options, defaultOptions);

                                // Add tools if provided
                                if (tools != null && !tools.isEmpty()) {
                                    formatter.applyTools(request, tools);
                                }

                                if (streamEnabled) {
                                    // Make streaming API call
                                    return AnthropicResponseParser.parseStreamEvents(
                                            client.stream(apiKey, baseUrl, request, options),
                                            startTime);
                                } else {
                                    // For non-streaming, make a single call
                                    return Mono.fromCallable(
                                                    () ->
                                                            client.call(
                                                                    apiKey, baseUrl, request,
                                                                    options))
                                            .map(
                                                    response ->
                                                            formatter.parseResponse(
                                                                    response, startTime))
                                            .flux();
                                }
                            } catch (Exception e) {
                                return Flux.error(
                                        new ModelException(
                                                "Failed to stream Anthropic API: " + e.getMessage(),
                                                e,
                                                modelName,
                                                "anthropic"));
                            }
                        });

        // Apply timeout and retry if configured
        return ModelUtils.applyTimeoutAndRetry(
                responseFlux, options, defaultOptions, modelName, "anthropic");
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
     * Creates a builder for constructing AnthropicChatModel instances.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for AnthropicChatModel. */
    public static class Builder {
        private String baseUrl;
        private String apiKey;
        private String modelName = "claude-sonnet-4-5-20250929";
        private boolean streamEnabled = true;
        private GenerateOptions defaultOptions;
        private AnthropicBaseFormatter formatter;
        private HttpTransport httpTransport;

        /**
         * Sets the base URL for the Anthropic API.
         *
         * @param baseUrl the base URL
         * @return this builder
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the API key for authentication.
         *
         * @param apiKey the API key
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the model name.
         *
         * @param modelName the model name
         * @return this builder
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * Enables or disables streaming.
         *
         * @param streamEnabled true to enable streaming
         * @return this builder
         */
        public Builder stream(boolean streamEnabled) {
            this.streamEnabled = streamEnabled;
            return this;
        }

        /**
         * Sets default generation options.
         *
         * @param defaultOptions the default options
         * @return this builder
         */
        public Builder defaultOptions(GenerateOptions defaultOptions) {
            this.defaultOptions = defaultOptions;
            return this;
        }

        /**
         * Sets a custom formatter.
         *
         * @param formatter the formatter
         * @return this builder
         */
        public Builder formatter(AnthropicBaseFormatter formatter) {
            this.formatter = formatter;
            return this;
        }

        /**
         * Sets the HTTP transport to use.
         *
         * @param httpTransport the HTTP transport
         * @return this builder
         */
        public Builder httpTransport(HttpTransport httpTransport) {
            this.httpTransport = httpTransport;
            return this;
        }

        /**
         * Builds the AnthropicChatModel instance.
         *
         * @return a new AnthropicChatModel
         */
        public AnthropicChatModel build() {
            return new AnthropicChatModel(
                    baseUrl,
                    apiKey,
                    modelName,
                    streamEnabled,
                    defaultOptions,
                    formatter,
                    httpTransport);
        }
    }
}
