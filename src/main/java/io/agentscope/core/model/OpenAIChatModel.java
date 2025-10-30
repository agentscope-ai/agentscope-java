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

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.formatter.OpenAIChatFormatter;
import io.agentscope.core.message.Msg;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * OpenAI Chat Model implementation using the official OpenAI Java SDK v3.5.3.
 * This implementation provides complete integration with OpenAI's Chat Completion API,
 * including tool calling and streaming support.
 */
public class OpenAIChatModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(OpenAIChatModel.class);

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final ChatModel model;
    private final boolean streamEnabled;
    private final OpenAIClient client;
    private final GenerateOptions defaultOptions;
    private final Formatter<ChatCompletionMessageParam, Object, ChatCompletionCreateParams.Builder>
            formatter;

    /**
     * Creates a new OpenAI chat model instance.
     *
     * @param baseUrl the base URL for OpenAI API (null for default)
     * @param apiKey the API key for authentication (null for no authentication)
     * @param modelName the model name to use (e.g., "gpt-4", "gpt-3.5-turbo")
     * @param streamEnabled whether streaming should be enabled
     * @param defaultOptions default generation options
     * @param formatter the message formatter to use (null for default OpenAI formatter)
     */
    public OpenAIChatModel(
            String baseUrl,
            String apiKey,
            String modelName,
            boolean streamEnabled,
            GenerateOptions defaultOptions,
            Formatter<ChatCompletionMessageParam, Object, ChatCompletionCreateParams.Builder>
                    formatter) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.model = ChatModel.of(modelName);
        this.streamEnabled = streamEnabled;
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : GenerateOptions.builder().build();
        this.formatter = formatter != null ? formatter : new OpenAIChatFormatter();

        // Initialize OpenAI client
        OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder();

        if (apiKey != null) {
            clientBuilder.apiKey(apiKey);
        }

        if (baseUrl != null) {
            clientBuilder.baseUrl(baseUrl);
        }

        this.client = clientBuilder.build();
    }

    /**
     * Stream chat completion responses from OpenAI's API.
     *
     * <p>This method internally handles message formatting using the configured formatter.
     * It supports both streaming and non-streaming modes based on the streamEnabled setting.
     *
     * @param messages AgentScope messages to send to the model
     * @param tools Optional list of tool schemas (null or empty if no tools)
     * @param options Optional generation options (null to use defaults)
     * @return Flux stream of chat responses
     */
    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Instant startTime = Instant.now();
        log.debug(
                "OpenAI stream: model={}, messages={}, tools_present={}",
                model,
                messages != null ? messages.size() : 0,
                tools != null && !tools.isEmpty());

        return Flux.defer(
                () -> {
                    try {
                        // Build chat completion request
                        ChatCompletionCreateParams.Builder paramsBuilder =
                                ChatCompletionCreateParams.builder().model(model);

                        // Use formatter to convert Msg to OpenAI ChatCompletionMessageParam
                        List<ChatCompletionMessageParam> formattedMessages =
                                formatter.format(messages);
                        for (ChatCompletionMessageParam param : formattedMessages) {
                            paramsBuilder.addMessage(param);
                        }

                        // Add tools if provided
                        if (tools != null && !tools.isEmpty()) {
                            formatter.applyTools(paramsBuilder, tools);
                        }

                        // Apply generation options via formatter
                        formatter.applyOptions(paramsBuilder, options, defaultOptions);

                        // Create the request
                        ChatCompletionCreateParams params = paramsBuilder.build();

                        if (streamEnabled) {
                            // Make streaming API call
                            StreamResponse<ChatCompletionChunk> streamResponse =
                                    client.chat().completions().createStreaming(params);

                            // Convert the SDK's Stream to Flux
                            return Flux.fromStream(streamResponse.stream())
                                    .map(chunk -> formatter.parseResponse(chunk, startTime))
                                    .filter(Objects::nonNull)
                                    .doFinally(
                                            signalType -> {
                                                try {
                                                    streamResponse.close();
                                                } catch (Exception ignored) {
                                                }
                                            });
                        } else {
                            // For non-streaming, make a single call and return as Flux
                            ChatCompletion completion = client.chat().completions().create(params);
                            ChatResponse response = formatter.parseResponse(completion, startTime);
                            return Flux.just(response);
                        }
                    } catch (Exception e) {
                        return Flux.error(
                                new RuntimeException(
                                        "Failed to stream OpenAI API: " + e.getMessage(), e));
                    }
                });
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
     * Creates a new builder for OpenAIChatModel.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private boolean streamEnabled = true;
        private GenerateOptions defaultOptions = GenerateOptions.builder().build();
        private Formatter<ChatCompletionMessageParam, Object, ChatCompletionCreateParams.Builder>
                formatter;

        /**
         * Sets the base URL for OpenAI API.
         *
         * @param baseUrl the base URL (null for default OpenAI API)
         * @return this builder instance
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the API key for authentication.
         *
         * @param apiKey the API key (null for no authentication)
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
         * @param streamEnabled true to enable streaming, false for non-streaming
         * @return this builder instance
         */
        public Builder stream(boolean streamEnabled) {
            this.streamEnabled = streamEnabled;
            return this;
        }

        /**
         * Sets the default generation options.
         *
         * @param options the default options to use
         * @return this builder instance
         */
        public Builder defaultOptions(GenerateOptions options) {
            this.defaultOptions = options;
            return this;
        }

        /**
         * Sets the message formatter to use.
         *
         * @param formatter the formatter (null for default OpenAI formatter)
         * @return this builder instance
         */
        public Builder formatter(
                Formatter<ChatCompletionMessageParam, Object, ChatCompletionCreateParams.Builder>
                        formatter) {
            this.formatter = formatter;
            return this;
        }

        /**
         * Builds a new OpenAIChatModel instance with the set values.
         *
         * @return a new OpenAIChatModel instance
         */
        public OpenAIChatModel build() {
            return new OpenAIChatModel(
                    baseUrl, apiKey, modelName, streamEnabled, defaultOptions, formatter);
        }
    }
}
