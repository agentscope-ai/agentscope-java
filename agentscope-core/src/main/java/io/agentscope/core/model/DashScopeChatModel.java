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
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.formatter.dashscope.dto.DashScopeMessage;
import io.agentscope.core.formatter.dashscope.dto.DashScopeRequest;
import io.agentscope.core.formatter.dashscope.dto.DashScopeResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * DashScope Chat Model using native HTTP API.
 *
 * <p>This implementation uses direct HTTP calls to DashScope API via OkHttp,
 * without depending on the DashScope Java SDK.
 *
 * <p>Supports both text and vision models through automatic endpoint routing:
 * <ul>
 *   <li>Vision models (names starting with "qvq" or containing "-vl") use MultiModalGeneration API
 *   <li>Text models use TextGeneration API
 * </ul>
 *
 * <p>Features:
 * <ul>
 *   <li>Streaming and non-streaming modes</li>
 *   <li>Tool calling support</li>
 *   <li>Thinking mode support</li>
 *   <li>Automatic message format conversion</li>
 *   <li>Timeout and retry configuration</li>
 * </ul>
 */
public class DashScopeChatModel extends ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(DashScopeChatModel.class);

    private final String modelName;
    private final boolean stream;
    private final Boolean enableThinking; // nullable
    private final Boolean enableSearch; // nullable
    private final GenerateOptions defaultOptions;
    private final Formatter<DashScopeMessage, DashScopeResponse, DashScopeRequest> formatter;

    // HTTP client for API calls
    private final DashScopeHttpClient httpClient;

    /**
     * Check if model requires MultiModal API based on model name.
     *
     * <p>Model names starting with "qvq" or containing "-vl" use the MultiModal API:
     * <ul>
     *   <li>Models starting with "qvq" (e.g., qvq-72b, qvq-7b) → MultiModal API</li>
     *   <li>Models containing "-vl" (e.g., qwen-vl-plus, qwen-vl-max) → MultiModal API</li>
     *   <li>All other models → Text Generation API</li>
     * </ul>
     *
     * @return true if model requires MultiModal API
     */
    private boolean requiresMultiModalApi() {
        if (modelName == null) {
            return false;
        }
        return modelName.startsWith("qvq") || modelName.contains("-vl");
    }

    /**
     * Creates a new DashScope chat model instance.
     *
     * @param apiKey the API key for DashScope authentication
     * @param modelName the model name (e.g., "qwen-max", "qwen-vl-plus")
     * @param stream whether streaming should be enabled (ignored if enableThinking is true)
     * @param enableThinking whether thinking mode should be enabled (null for disabled)
     * @param enableSearch whether search enhancement should be enabled (null for disabled)
     * @param defaultOptions default generation options (null for defaults)
     * @param baseUrl custom base URL for DashScope API (null for default)
     * @param formatter the message formatter to use (null for default DashScope formatter)
     * @param httpTransport custom HTTP transport (null for default from factory)
     * @param publicKeyId the RSA public key ID for encryption (null to disable encryption)
     * @param publicKey the RSA public key for encryption (Base64-encoded, null to disable encryption)
     */
    public DashScopeChatModel(
            String apiKey,
            String modelName,
            boolean stream,
            Boolean enableThinking,
            Boolean enableSearch,
            GenerateOptions defaultOptions,
            String baseUrl,
            Formatter<DashScopeMessage, DashScopeResponse, DashScopeRequest> formatter,
            HttpTransport httpTransport,
            String publicKeyId,
            String publicKey) {
        this.modelName = modelName;
        // Thinking mode requires streaming; override stream setting if needed
        if (enableThinking != null && enableThinking && !stream) {
            log.info(
                    "Thinking mode is enabled but stream=false was specified. "
                            + "Forcing stream=true as thinking mode requires streaming.");
        }
        this.stream = enableThinking != null && enableThinking ? true : stream;
        this.enableThinking = enableThinking;
        this.enableSearch = enableSearch;
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : GenerateOptions.builder().build();
        this.formatter = formatter != null ? formatter : new DashScopeChatFormatter();

        // Initialize HTTP client with provided transport or factory default
        HttpTransport transport =
                httpTransport != null ? httpTransport : HttpTransportFactory.getDefault();
        this.httpClient =
                DashScopeHttpClient.builder()
                        .transport(transport)
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .publicKeyId(publicKeyId)
                        .publicKey(publicKey)
                        .build();
    }

    /**
     * Creates a new builder for DashScopeChatModel.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Stream chat completion responses from DashScope's API.
     *
     * <p>This method automatically routes to the appropriate API based on the model name:
     * <ul>
     *   <li>Vision models (qvq* or *-vl*) → MultiModal API</li>
     *   <li>Text models → Text Generation API</li>
     * </ul>
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

        log.debug(
                "DashScope API call: model={}, multimodal={}", modelName, requiresMultiModalApi());

        Flux<ChatResponse> responseFlux = streamWithHttpClient(messages, tools, options);

        // Apply timeout and retry if configured
        return ModelUtils.applyTimeoutAndRetry(
                responseFlux, options, defaultOptions, modelName, "dashscope");
    }

    /**
     * Stream using HTTP client.
     *
     * <p>This method uses the native DashScope HTTP API directly via OkHttp.
     */
    private Flux<ChatResponse> streamWithHttpClient(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Instant start = Instant.now();
        boolean useMultimodal = requiresMultiModalApi();

        // Get effective options
        GenerateOptions effectiveOptions = options != null ? options : defaultOptions;
        ToolChoice toolChoice = effectiveOptions.getToolChoice();

        // Format messages using formatter
        List<DashScopeMessage> dashScopeMessages;
        if (useMultimodal) {
            if (formatter instanceof DashScopeChatFormatter chatFormatter) {
                dashScopeMessages = chatFormatter.formatMultiModal(messages);
            } else if (formatter instanceof DashScopeMultiAgentFormatter multiAgentFormatter) {
                dashScopeMessages = multiAgentFormatter.formatMultiModal(messages);
            } else {
                throw new IllegalStateException(
                        "DashScope vision models require DashScopeChatFormatter or"
                                + " DashScopeMultiAgentFormatter, but got: "
                                + formatter.getClass().getName());
            }
        } else {
            dashScopeMessages = formatter.format(messages);
        }

        // Build request using formatter
        DashScopeRequest request;
        if (formatter instanceof DashScopeChatFormatter chatFormatter) {
            request =
                    chatFormatter.buildRequest(
                            modelName,
                            dashScopeMessages,
                            stream,
                            options,
                            defaultOptions,
                            tools,
                            toolChoice);
        } else if (formatter instanceof DashScopeMultiAgentFormatter multiAgentFormatter) {
            request = multiAgentFormatter.buildRequest(modelName, dashScopeMessages, stream);
            // Apply options and tools manually for multi-agent formatter
            multiAgentFormatter.applyOptions(request, options, defaultOptions);
            multiAgentFormatter.applyTools(request, tools);
            multiAgentFormatter.applyToolChoice(request, toolChoice);
        } else {
            throw new IllegalStateException(
                    "Unsupported formatter type: " + formatter.getClass().getName());
        }

        // Apply thinking mode if enabled
        applyThinkingMode(request, effectiveOptions);

        if (stream) {
            // Streaming mode
            return httpClient.stream(
                            request,
                            effectiveOptions.getAdditionalHeaders(),
                            effectiveOptions.getAdditionalBodyParams(),
                            effectiveOptions.getAdditionalQueryParams())
                    .map(response -> formatter.parseResponse(response, start));
        } else {
            // Non-streaming mode
            return Flux.defer(
                    () -> {
                        try {
                            DashScopeResponse response =
                                    httpClient.call(
                                            request,
                                            effectiveOptions.getAdditionalHeaders(),
                                            effectiveOptions.getAdditionalBodyParams(),
                                            effectiveOptions.getAdditionalQueryParams());
                            ChatResponse chatResponse = formatter.parseResponse(response, start);
                            return Flux.just(chatResponse);
                        } catch (Exception e) {
                            log.error("DashScope HTTP client error: {}", e.getMessage(), e);
                            return Flux.error(
                                    new RuntimeException(
                                            "DashScope API call failed: " + e.getMessage(), e));
                        }
                    });
        }
    }

    /**
     * Apply thinking mode configuration to request if enabled.
     */
    private void applyThinkingMode(DashScopeRequest request, GenerateOptions options) {
        // Validate thinking configuration
        if (options.getThinkingBudget() != null && !Boolean.TRUE.equals(enableThinking)) {
            throw new IllegalStateException(
                    "thinkingBudget is set but enableThinking is not enabled. To use thinking mode"
                        + " with budget control, you must explicitly enable thinking by calling"
                        + " .enableThinking(true) on the model builder. Example:"
                        + " DashScopeChatModel.builder().enableThinking(true)"
                        + ".defaultOptions(GenerateOptions.builder().thinkingBudget(1000).build())");
        }

        if (enableThinking != null) {
            // Explicitly assign value for thinking mode
            request.getParameters().setEnableThinking(enableThinking);
        }

        if (Boolean.TRUE.equals(enableThinking) && options.getThinkingBudget() != null) {
            request.getParameters().setThinkingBudget(options.getThinkingBudget());
        }

        // Model-specific settings for search mode
        if (enableSearch != null) {
            // Explicitly assign value for search mode
            request.getParameters().setEnableSearch(enableSearch);
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

    public static class Builder {
        private String apiKey;
        private String modelName;
        private boolean stream = true;
        private Boolean enableThinking;
        private Boolean enableSearch;
        private GenerateOptions defaultOptions = null;
        private String baseUrl;
        private Formatter<DashScopeMessage, DashScopeResponse, DashScopeRequest> formatter;
        private HttpTransport httpTransport;
        private boolean enableEncrypt = false;

        /**
         * Sets the API key for DashScope authentication.
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
         * <p>The model name determines which API is used:
         * <ul>
         *   <li>Vision models (qvq* or *-vl*) → MultiModal API</li>
         *   <li>Text models → Text Generation API</li>
         * </ul>
         *
         * @param modelName the model name (e.g., "qwen-max", "qwen-vl-plus")
         * @return this builder instance
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * Sets whether streaming should be enabled.
         *
         * <p>This setting is ignored if enableThinking is set to true, as thinking mode
         * automatically enables streaming.
         *
         * @param stream true to enable streaming, false for non-streaming
         * @return this builder instance
         */
        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        /**
         * Sets whether thinking mode should be enabled.
         *
         * <p>When enabled, this automatically enables streaming and may override the stream setting.
         * Thinking mode allows the model to show its reasoning process.
         *
         * @param enableThinking true to enable thinking mode, false to disable, null for default
         * @return this builder instance
         */
        public Builder enableThinking(Boolean enableThinking) {
            this.enableThinking = enableThinking;
            return this;
        }

        /**
         * Sets whether search enhancement should be enabled.
         *
         * <p>When enabled, the model can access internet search to provide more up-to-date
         * and accurate responses.
         *
         * @param enableSearch true to enable search mode, false to disable, null for default (disabled)
         * @return this builder instance
         */
        public Builder enableSearch(Boolean enableSearch) {
            this.enableSearch = enableSearch;
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
         * Sets a custom base URL for DashScope API.
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
         * @param formatter the formatter (null for default DashScope formatter)
         * @return this builder instance
         */
        public Builder formatter(
                Formatter<DashScopeMessage, DashScopeResponse, DashScopeRequest> formatter) {
            this.formatter = formatter;
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
         * DashScopeChatModel model = DashScopeChatModel.builder()
         *     .apiKey("xxx")
         *     .modelName("qwen-plus")
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
         * Sets whether encryption should be enabled.
         *
         * <p>When enabled, the model will automatically fetch the latest RSA public key from
         * DashScope API and use it to encrypt requests and responses using AES-GCM with RSA key
         * exchange, following Aliyun's encryption protocol. This enables secure access to Aliyun
         * models while complying with enterprise security policies (e.g., TLS encryption,
         * token-based authentication).
         *
         * <p>If fetching the public key fails during build(), an exception will be thrown to
         * prevent creating a model with incorrect encryption configuration.
         *
         * <p>Example:
         * <pre>{@code
         * DashScopeChatModel model = DashScopeChatModel.builder()
         *     .apiKey("sk-xxx")
         *     .modelName("qwen-max")
         *     .enableEncrypt(true)
         *     .build();
         * }</pre>
         *
         * @param enableEncrypt true to enable encryption (will fetch public key automatically),
         *     false to disable encryption
         * @return this builder instance
         */
        public Builder enableEncrypt(boolean enableEncrypt) {
            this.enableEncrypt = enableEncrypt;
            return this;
        }

        /**
         * Builds the DashScopeChatModel instance.
         *
         * <p>This method ensures that the defaultOptions always has proper executionConfig
         * applied.
         *
         * <p>If encryption is enabled, this method will automatically fetch the public key
         * from DashScope API. If the fetch fails, an exception will be thrown.
         *
         * @return configured DashScopeChatModel instance
         * @throws DashScopeHttpClient.DashScopeHttpException if encryption is enabled and
         *     public key fetching fails
         */
        public DashScopeChatModel build() {
            GenerateOptions effectiveOptions =
                    ModelUtils.ensureDefaultExecutionConfig(defaultOptions);

            String finalPublicKeyId = null;
            String finalPublicKey = null;

            if (enableEncrypt) {
                HttpTransport transport =
                        httpTransport != null ? httpTransport : HttpTransportFactory.getDefault();
                DashScopeHttpClient.PublicKeyResult publicKeyResult =
                        DashScopeHttpClient.fetchPublicKey(apiKey, baseUrl, transport);
                finalPublicKeyId = publicKeyResult.publicKeyId();
                finalPublicKey = publicKeyResult.publicKey();
            }

            return new DashScopeChatModel(
                    apiKey,
                    modelName,
                    stream,
                    enableThinking,
                    enableSearch,
                    effectiveOptions,
                    baseUrl,
                    formatter,
                    httpTransport,
                    finalPublicKeyId,
                    finalPublicKey);
        }
    }
}
