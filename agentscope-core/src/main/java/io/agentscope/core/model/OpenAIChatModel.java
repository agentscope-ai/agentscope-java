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
import io.agentscope.core.formatter.openai.ProviderCapability;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Stateless OpenAI Chat Model using native HTTP API.
 *
 * <p>This implementation uses direct HTTP calls to OpenAI-compatible APIs. All configuration
 * (API key, base URL, model name, streaming mode) is passed per-request via {@link GenerateOptions},
 * making this model stateless and safe to share across multiple threads.
 *
 * <p>Features:
 * <ul>
 *   <li>Streaming and non-streaming modes</li>
 *   <li>Tool calling support</li>
 *   <li>Automatic message format conversion</li>
 *   <li>Timeout and retry configuration</li>
 *   <li>Multi-provider support (OpenAI, GLM, DeepSeek, Doubao, etc.)</li>
 * </ul>
 *
 * <p><b>Usage example:</b>
 * <pre>{@code
 * // Create a stateless model instance
 * OpenAIChatModel model = new OpenAIChatModel();
 *
 * // Configure once
 * OpenAIConfig config = OpenAIConfig.builder()
 *     .apiKey("sk-xxx")
 *     .baseUrl("https://api.openai.com/v1")
 *     .stream(true)
 *     .build();
 *
 * // Use with different models
 * GenerateOptions opts1 = config.toOptions().modelName("gpt-4").build();
 * GenerateOptions opts2 = config.toOptions().modelName("gpt-3.5-turbo").build();
 *
 * model.stream(messages1, null, opts1);
 * model.stream(messages2, null, opts2);
 * }</pre>
 */
public class OpenAIChatModel extends ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(OpenAIChatModel.class);

    private final OpenAIClient client;
    private final Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter;

    /**
     * Creates a new stateless OpenAI chat model instance with default client and formatter.
     */
    public OpenAIChatModel() {
        this(new OpenAIClient(), new OpenAIChatFormatter());
    }

    /**
     * Creates a new stateless OpenAI chat model instance.
     *
     * @param client the OpenAI HTTP client
     * @param formatter the message formatter
     */
    public OpenAIChatModel(
            OpenAIClient client,
            Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter) {
        this.client = client != null ? client : new OpenAIClient();
        this.formatter = formatter != null ? formatter : new OpenAIChatFormatter();
    }

    /**
     * Creates a new stateless OpenAI chat model instance with default formatter.
     *
     * @param client the OpenAI HTTP client
     */
    public OpenAIChatModel(OpenAIClient client) {
        this(client, new OpenAIChatFormatter());
    }

    /**
     * Creates a new stateless OpenAI chat model instance with default client.
     *
     * @param formatter the message formatter
     */
    public OpenAIChatModel(Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter) {
        this(new OpenAIClient(), formatter);
    }

    /**
     * Creates a new stateless OpenAI chat model instance with custom transport.
     *
     * @param transport the HTTP transport
     * @param formatter the message formatter (null for default)
     */
    public OpenAIChatModel(
            HttpTransport transport,
            Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter) {
        this(new OpenAIClient(transport), formatter);
    }

    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {

        if (options == null || options.getModelName() == null) {
            throw new IllegalArgumentException(
                    "modelName must be specified in GenerateOptions for stateless OpenAIChatModel");
        }

        String modelName = options.getModelName();
        log.debug("OpenAI API call: model={}", modelName);

        // Determine streaming mode (options.stream takes precedence)
        boolean stream = options.getStream() != null ? options.getStream() : false;

        // Get apiKey and baseUrl from options
        String apiKey = options.getApiKey();
        String baseUrl = options.getBaseUrl();

        Instant start = Instant.now();

        // Detect provider capability (use user-specified if provided)
        ProviderCapability capability = options.getProviderCapability();
        if (capability == null) {
            capability = ProviderCapability.UNKNOWN;
            if (baseUrl != null) {
                capability = ProviderCapability.fromUrl(baseUrl);
            }
            if (capability == ProviderCapability.UNKNOWN && modelName != null) {
                capability = ProviderCapability.fromModelName(modelName);
            }
        }

        // Format messages using formatter
        List<OpenAIMessage> openaiMessages = formatter.format(messages);

        // Apply provider-specific message format fixes
        openaiMessages = applyProviderMessageFixes(openaiMessages, capability);

        // Build request using formatter
        OpenAIRequest.Builder requestBuilder =
                OpenAIRequest.builder().model(modelName).messages(openaiMessages).stream(stream);

        // Include usage in stream_options for all streaming calls
        // This ensures token usage information is available in the final response chunk
        // Required by OpenAI-compatible APIs like DashScope, Bailian, etc.
        if (stream) {
            requestBuilder.streamOptions(
                    new io.agentscope.core.formatter.openai.dto.OpenAIStreamOptions(true));
        }

        OpenAIRequest request = requestBuilder.build();

        // Apply tools to request
        if (tools != null && !tools.isEmpty()) {
            formatter.applyTools(request, tools, baseUrl, modelName);
        }

        // Apply generation options
        formatter.applyOptions(request, options, null);

        // Apply tool choice if specified
        if (options.getToolChoice() != null) {
            formatter.applyToolChoice(request, options.getToolChoice(), baseUrl, modelName);
        }

        // Make the API call
        if (stream) {
            // Streaming mode
            return client.stream(apiKey, baseUrl, request, options)
                    .map(response -> formatter.parseResponse(response, start))
                    .filter(Objects::nonNull);
        } else {
            // Non-streaming mode: make a single call and return as Flux
            return Flux.defer(
                    () -> {
                        try {
                            OpenAIResponse response =
                                    client.call(apiKey, baseUrl, request, options);
                            ChatResponse chatResponse = formatter.parseResponse(response, start);
                            return Flux.just(chatResponse);
                        } catch (Exception e) {
                            return Flux.error(
                                    new ModelException(
                                            "Failed to call OpenAI API: " + e.getMessage(),
                                            e,
                                            modelName,
                                            "openai"));
                        }
                    });
        }
    }

    /**
     * Apply provider-specific message format fixes.
     *
     * <p>This method handles provider-specific message format requirements that cannot be
     * handled by the standard formatter.
     *
     * @param messages the formatted OpenAI messages
     * @param capability the provider capability
     * @return the adjusted messages
     */
    private static List<OpenAIMessage> applyProviderMessageFixes(
            List<OpenAIMessage> messages, ProviderCapability capability) {

        if (capability == ProviderCapability.GLM) {
            // GLM API requires at least one user message in the conversation
            boolean hasUserMessage = false;
            for (OpenAIMessage msg : messages) {
                if ("user".equals(msg.getRole())) {
                    hasUserMessage = true;
                    break;
                }
            }

            if (!hasUserMessage) {
                // GLM API returns error 1214 if there's no user message
                // Add a placeholder user message at the end
                log.debug(
                        "GLM provider detected: adding placeholder user message to satisfy API"
                                + " requirement");
                OpenAIMessage placeholderUserMessage =
                        OpenAIMessage.builder().role("user").content("Please proceed.").build();
                List<OpenAIMessage> adjustedMessages = new ArrayList<>(messages);
                adjustedMessages.add(placeholderUserMessage);
                return adjustedMessages;
            }
        }

        // DeepSeek: Fix message format
        // DeepSeek API requires:
        // 1. No reasoning_content in request messages
        // 2. No system role (convert to user)
        // 3. No name field in messages (DeepSeek returns HTTP 400 if name is present)
        // 4. Messages must end with user role (to allow the model to respond)
        if (capability == ProviderCapability.DEEPSEEK) {
            boolean needsFix = false;
            // Check if reasoning_content, system message, or name field exists
            for (OpenAIMessage msg : messages) {
                if (msg.getReasoningContent() != null
                        || "system".equals(msg.getRole())
                        || msg.getName() != null) {
                    needsFix = true;
                    break;
                }
            }
            // Check if last message is assistant (need to add user message to continue)
            if (!needsFix
                    && !messages.isEmpty()
                    && "assistant".equals(messages.get(messages.size() - 1).getRole())) {
                needsFix = true;
            }

            if (needsFix) {
                log.debug("DeepSeek provider detected: fixing message format");
                List<OpenAIMessage> adjustedMessages = new ArrayList<>();
                for (OpenAIMessage msg : messages) {
                    // Convert system message to user
                    String role = msg.getRole();
                    if ("system".equals(role)) {
                        role = "user";
                    }

                    // Build new message without reasoning_content and name
                    OpenAIMessage.Builder builder = OpenAIMessage.builder().role(role);
                    // Handle content (could be String or List)
                    Object content = msg.getContent();
                    if (content instanceof String) {
                        builder.content((String) content);
                    } else if (content instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<io.agentscope.core.formatter.openai.dto.OpenAIContentPart>
                                contentParts =
                                        (List<
                                                        io.agentscope.core.formatter.openai.dto
                                                                .OpenAIContentPart>)
                                                content;
                        builder.content(contentParts);
                    }
                    // Note: Don't include name field for DeepSeek
                    if (msg.getToolCalls() != null) {
                        builder.toolCalls(msg.getToolCalls());
                    }
                    if (msg.getToolCallId() != null) {
                        builder.toolCallId(msg.getToolCallId());
                    }
                    adjustedMessages.add(builder.build());
                }
                // If last message is assistant, add a user message to continue
                if (!adjustedMessages.isEmpty()
                        && "assistant"
                                .equals(
                                        adjustedMessages
                                                .get(adjustedMessages.size() - 1)
                                                .getRole())) {
                    adjustedMessages.add(
                            OpenAIMessage.builder()
                                    .role("user")
                                    .content("Please continue.")
                                    .build());
                }
                return adjustedMessages;
            }
        }

        return messages;
    }

    /**
     * Gets the OpenAI client used by this model.
     *
     * @return the OpenAI client
     */
    public OpenAIClient getClient() {
        return client;
    }

    /**
     * Gets the HTTP transport used by this model.
     *
     * @return the HTTP transport
     */
    public HttpTransport getTransport() {
        return client.getTransport();
    }

    /**
     * Gets the model name.
     *
     * <p>For stateless models, this returns a generic identifier since the actual model name
     * is provided per-request via {@link GenerateOptions#getModelName()}.
     *
     * @return "stateless" as the model identifier
     */
    @Override
    public String getModelName() {
        return "stateless";
    }

    /**
     * Creates a new builder for OpenAIChatModel.
     *
     * <p>The builder provides backward compatibility with the old API by creating a model
     * with embedded configuration. The built model wraps the configuration in a way that
     * each call uses the configured values.
     *
     * @return a new Builder instance
     * @deprecated Use {@link OpenAIConfig} with {@link GenerateOptions} for stateless usage
     */
    @Deprecated
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for OpenAIChatModel.
     *
     * <p>Provides backward compatibility with the old API. The built model internally wraps
     * the configuration so that calls without explicit options use the builder-provided values.
     *
     * @deprecated Use {@link OpenAIConfig} with {@link GenerateOptions} for stateless usage
     */
    @Deprecated
    public static class Builder {
        private String apiKey;
        private String modelName;
        private Boolean stream;
        private GenerateOptions defaultOptions;
        private String baseUrl;
        private Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter;
        private HttpTransport httpTransport;
        private String reasoningEffort;
        private io.agentscope.core.formatter.openai.ProviderCapability providerCapability;

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
         * @param reasoningEffort the reasoning effort ("low", "medium", "high")
         * @return this builder instance
         */
        public Builder reasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
            return this;
        }

        /**
         * Sets the provider capability for this model.
         *
         * <p>When set, this explicitly specifies the provider's capability (tool_choice support, etc.)
         * instead of auto-detecting from baseUrl or modelName.
         *
         * @param providerCapability the provider capability to use
         * @return this builder instance
         */
        public Builder providerCapability(
                io.agentscope.core.formatter.openai.ProviderCapability providerCapability) {
            this.providerCapability = providerCapability;
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
         * <p>This creates a {@link ConfiguredModel} that wraps the configuration provided
         * to the builder, allowing backward compatibility with code that expects the old
         * stateful API.
         *
         * @return configured OpenAIChatModel instance
         * @throws IllegalArgumentException if modelName is not set
         */
        public OpenAIChatModel build() {
            Objects.requireNonNull(modelName, "modelName must be set");

            // Ensure default options has execution config
            GenerateOptions effectiveOptions =
                    ModelUtils.ensureDefaultExecutionConfig(defaultOptions);

            // Apply reasoning effort if set
            if (reasoningEffort != null) {
                effectiveOptions =
                        GenerateOptions.builder()
                                .apiKey(apiKey)
                                .baseUrl(baseUrl)
                                .modelName(modelName)
                                .stream(stream)
                                .providerCapability(providerCapability)
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
            } else {
                // Merge builder config into effective options
                effectiveOptions =
                        GenerateOptions.builder()
                                .apiKey(apiKey)
                                .baseUrl(baseUrl)
                                .modelName(modelName)
                                .stream(stream)
                                .providerCapability(providerCapability)
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
                                .build();
            }

            // Create transport
            HttpTransport transport =
                    httpTransport != null ? httpTransport : HttpTransportFactory.getDefault();
            OpenAIClient client = new OpenAIClient(transport);
            Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> fmt =
                    formatter != null ? formatter : new OpenAIChatFormatter();

            // Return a configured model that wraps the options
            return new ConfiguredModel(client, fmt, effectiveOptions);
        }
    }

    /**
     * Internal model that has pre-configured options for backward compatibility.
     *
     * <p>This wraps the stateless model with pre-configured options, mimicking the old
     * stateful behavior where modelName, apiKey, baseUrl were stored in the model instance.
     */
    private static class ConfiguredModel extends OpenAIChatModel {
        private final GenerateOptions configuredOptions;

        ConfiguredModel(
                OpenAIClient client,
                Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter,
                GenerateOptions configuredOptions) {
            super(client, formatter);
            this.configuredOptions = configuredOptions;
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            // Merge provided options with configured options (provided takes precedence)
            GenerateOptions effectiveOptions =
                    GenerateOptions.mergeOptions(options, configuredOptions);
            return super.doStream(messages, tools, effectiveOptions);
        }

        @Override
        public String getModelName() {
            return configuredOptions.getModelName();
        }
    }
}
