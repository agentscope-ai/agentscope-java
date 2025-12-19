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
import com.openai.models.ChatModel;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import io.agentscope.core.Version;
import io.agentscope.core.formatter.openai.OpenAIResponseParser;
import io.agentscope.core.formatter.openai.OpenAIToolsHelper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tracing.TracerRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * OpenAI Reasoning Model implementation using Responses API.
 *
 * <p>This model is specifically designed for OpenAI's reasoning models (GPT-5, O1, O4, etc.)
 * that support extended thinking/reasoning capabilities through the Responses API.
 *
 * <p><strong>Key Features:</strong>
 * <ul>
 *   <li>Native support for reasoning_effort parameter (low, medium, high)
 *   <li>Proper reasoning token accounting and extraction
 *   <li>Designed for complex problem-solving tasks
 *   <li>Full integration with Responses API (not Chat Completions)
 * </ul>
 *
 * <p><strong>Important Notes:</strong>
 * <ul>
 *   <li>Use this model for O-series and reasoning-capable models</li>
 *   <li>The {@code reasoningEffort} parameter controls thinking depth (low/medium/high)</li>
 *   <li>Reasoning tokens are billed as output tokens</li>
 *   <li>Recommended to reserve 25,000+ tokens for reasoning and outputs</li>
 * </ul>
 *
 * @see <a href="https://platform.openai.com/docs/guides/reasoning">OpenAI Reasoning Guide</a>
 */
public class OpenAIReasoningModel extends ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(OpenAIReasoningModel.class);

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final ChatModel model;
    private final OpenAIClient client;
    private final GenerateOptions defaultOptions;
    private final ReasoningEffort reasoningEffort;
    private final OpenAIResponseParser responseParser;
    private final OpenAIToolsHelper toolsHelper;

    /**
     * Creates a new OpenAI Reasoning Model instance.
     *
     * @param baseUrl the base URL for OpenAI API (null for default)
     * @param apiKey the API key for authentication (null for no authentication)
     * @param modelName the model name (e.g., "gpt-5", "o1", "o4")
     * @param defaultOptions default generation options
     * @param reasoningEffort the reasoning effort level: "LOW", "MEDIUM", or "HIGH" (null for default MEDIUM)
     */
    public OpenAIReasoningModel(
            String baseUrl,
            String apiKey,
            String modelName,
            GenerateOptions defaultOptions,
            String reasoningEffort) {

        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.model = ChatModel.of(modelName);
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : GenerateOptions.builder().build();
        this.reasoningEffort = parseReasoningEffort(reasoningEffort);
        this.responseParser = new OpenAIResponseParser();
        this.toolsHelper = new OpenAIToolsHelper();

        // Initialize OpenAI client
        OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder();

        if (apiKey != null) {
            clientBuilder.apiKey(apiKey);
        }

        if (baseUrl != null) {
            clientBuilder.baseUrl(baseUrl);
        }

        // Set unified AgentScope User-Agent
        clientBuilder.putHeader("User-Agent", Version.getUserAgent());

        this.client = clientBuilder.build();

        log.info(
                "Initialized OpenAIReasoningModel: model={}, reasoning_effort={}, version={}",
                modelName,
                this.reasoningEffort,
                Version.VERSION);
    }

    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Instant startTime = Instant.now();
        log.debug(
                "OpenAI Responses API stream: model={}, messages={}, tools_present={}",
                model,
                messages != null ? messages.size() : 0,
                tools != null && !tools.isEmpty());

        Flux<ChatResponse> responseFlux =
                Flux.deferContextual(
                        reactorCtx ->
                                TracerRegistry.get()
                                        .runWithContext(
                                                reactorCtx,
                                                () -> {
                                                    try {
                                                        // Build Responses API request parameters
                                                        ResponseCreateParams.Builder paramsBuilder =
                                                                ResponseCreateParams.builder()
                                                                        .model(model);

                                                        // Convert messages to simple text input
                                                        if (messages != null
                                                                && !messages.isEmpty()) {
                                                            // Extract text from the last message
                                                            Msg lastMsg =
                                                                    messages.get(
                                                                            messages.size() - 1);
                                                            String inputText =
                                                                    lastMsg.getTextContent();
                                                            if (inputText == null
                                                                    || inputText.isEmpty()) {
                                                                inputText = lastMsg.toString();
                                                            }
                                                            paramsBuilder.input(inputText);
                                                        }

                                                        // Apply reasoning effort
                                                        applyReasoningEffort(paramsBuilder);

                                                        // Apply generation options
                                                        GenerateOptions effectiveOptions =
                                                                options != null
                                                                        ? options
                                                                        : defaultOptions;
                                                        if (effectiveOptions != null) {
                                                            if (effectiveOptions.getMaxTokens()
                                                                            != null
                                                                    && effectiveOptions
                                                                                    .getMaxTokens()
                                                                            > 0) {
                                                                paramsBuilder.maxOutputTokens(
                                                                        (long)
                                                                                effectiveOptions
                                                                                        .getMaxTokens());
                                                            }
                                                            if (effectiveOptions.getTemperature()
                                                                    != null) {
                                                                paramsBuilder.temperature(
                                                                        effectiveOptions
                                                                                .getTemperature());
                                                            }
                                                            if (effectiveOptions.getTopP()
                                                                    != null) {
                                                                paramsBuilder.topP(
                                                                        effectiveOptions.getTopP());
                                                            }
                                                        }

                                                        // TODO: Add tools if provided
                                                        if (tools != null && !tools.isEmpty()) {
                                                            log.debug(
                                                                    "Tools provided but not yet"
                                                                        + " supported for Responses"
                                                                        + " API");
                                                        }

                                                        // Build the request
                                                        ResponseCreateParams params =
                                                                paramsBuilder.build();

                                                        // Make the API call
                                                        Response response =
                                                                client.responses().create(params);

                                                        // Parse and return response
                                                        ChatResponse chatResponse =
                                                                responseParser.parseResponse(
                                                                        response, startTime);
                                                        return Flux.just(chatResponse);
                                                    } catch (Exception e) {
                                                        log.error(
                                                                "Error streaming from OpenAI"
                                                                        + " Responses API",
                                                                e);
                                                        return Flux.error(
                                                                new ModelException(
                                                                        "Failed to stream OpenAI"
                                                                                + " Responses API: "
                                                                                + e.getMessage(),
                                                                        e,
                                                                        modelName,
                                                                        "openai-responses"));
                                                    }
                                                }));

        // Apply timeout and retry if configured
        return ModelUtils.applyTimeoutAndRetry(
                responseFlux, options, defaultOptions, modelName, "openai-responses", log);
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Apply reasoning effort parameter to the request builder.
     *
     * @param paramsBuilder the ResponseCreateParams builder
     */
    private void applyReasoningEffort(ResponseCreateParams.Builder paramsBuilder) {
        // Use instance default reasoning effort
        // Create Reasoning configuration with effort parameter
        Reasoning reasoning = Reasoning.builder().effort(this.reasoningEffort).build();

        paramsBuilder.reasoning(reasoning);
        log.debug("Applied reasoning effort: {}", this.reasoningEffort);
    }

    /**
     * Parse reasoning effort string to ReasoningEffort enum.
     *
     * @param effort the effort string: "LOW", "MEDIUM", "HIGH", etc. (case-insensitive)
     * @return the ReasoningEffort enum value, or MEDIUM if invalid
     */
    private static ReasoningEffort parseReasoningEffort(String effort) {
        if (effort == null) {
            return ReasoningEffort.MEDIUM;
        }

        try {
            return ReasoningEffort.of(effort.toUpperCase());
        } catch (Exception e) {
            log.warn(
                    "Invalid reasoning effort '{}'. Valid values are: LOW, MEDIUM, HIGH, etc. Using"
                            + " default MEDIUM.",
                    effort);
            return ReasoningEffort.MEDIUM;
        }
    }

    /**
     * Creates a new builder for OpenAIReasoningModel.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for OpenAIReasoningModel.
     */
    public static class Builder {
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private GenerateOptions defaultOptions;
        private String reasoningEffort = "MEDIUM";

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder defaultOptions(GenerateOptions options) {
            this.defaultOptions = options;
            return this;
        }

        /**
         * Sets the reasoning effort level.
         *
         * @param effort the effort level: "LOW", "MEDIUM", or "HIGH" (case-insensitive)
         * @return this builder instance
         */
        public Builder reasoningEffort(String effort) {
            this.reasoningEffort = effort;
            return this;
        }

        public OpenAIReasoningModel build() {
            Objects.requireNonNull(modelName, "Model name is required");

            GenerateOptions effectiveOptions =
                    ModelUtils.ensureDefaultExecutionConfig(defaultOptions);

            return new OpenAIReasoningModel(
                    baseUrl, apiKey, modelName, effectiveOptions, reasoningEffort);
        }
    }
}
