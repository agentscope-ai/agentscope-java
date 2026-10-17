/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.extensions.model.ollama;

import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelUtils;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.model.transport.OkHttpTransport;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.extensions.model.ollama.dto.OllamaMessage;
import io.agentscope.extensions.model.ollama.dto.OllamaRequest;
import io.agentscope.extensions.model.ollama.dto.OllamaResponse;
import io.agentscope.extensions.model.ollama.formatter.OllamaChatFormatter;
import io.agentscope.extensions.model.ollama.formatter.OllamaMultiAgentFormatter;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * AgentScope ChatModel implementation for Ollama.
 * Provides integration with local Ollama instances via HTTP API.
 *
 * <p>This class implements the AgentScope {@link ChatModelBase} interface for Ollama models,
 * supporting both synchronous and streaming chat operations, tool usage, and custom Ollama options.
 *
 * <p>Key features:
 * <ul>
 *   <li>Support for both {@link GenerateOptions} and Ollama-specific {@link OllamaOptions}</li>
 *   <li>Integration with AgentScope's message formatting system</li>
 *   <li>Streaming response support via Project Reactor's Flux</li>
 *   <li>Tool usage and function calling capabilities</li>
 *   <li>Configurable message formatters (OllamaChatFormatter, OllamaMultiAgentFormatter)</li>
 *   <li>Automatic timeout and retry mechanisms</li>
 * </ul>
 *
 */
public class OllamaChatModel extends ChatModelBase {
    private static final Logger log = LoggerFactory.getLogger(OllamaChatModel.class);

    private final String modelName;
    private final boolean stream;
    private final OllamaHttpClient httpClient;
    private final OllamaOptions defaultOptions;
    private final Formatter<OllamaMessage, OllamaResponse, OllamaRequest> formatter;

    /**
     * Creates a new OllamaChatModel.
     *
     * @param modelName The name of the model to use (e.g., "llama2", "mistral").
     * @param baseUrl The base URL of the Ollama server (e.g. "http://localhost:11434").
     * @param defaultOptions Default configuration options.
     * @param formatter The message formatter to use.
     * @param httpTransport The HTTP transport to use.
     */
    public OllamaChatModel(
            String modelName,
            String baseUrl,
            OllamaOptions defaultOptions,
            Formatter<OllamaMessage, OllamaResponse, OllamaRequest> formatter,
            HttpTransport httpTransport) {
        this(modelName, baseUrl, defaultOptions, formatter, httpTransport, true);
    }

    /**
     * Creates a new OllamaChatModel with an explicit streaming mode.
     */
    public OllamaChatModel(
            String modelName,
            String baseUrl,
            OllamaOptions defaultOptions,
            Formatter<OllamaMessage, OllamaResponse, OllamaRequest> formatter,
            HttpTransport httpTransport,
            boolean stream) {
        this.modelName = modelName;
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : OllamaOptions.builder().build();
        this.formatter = formatter != null ? formatter : new OllamaChatFormatter();
        this.stream = stream;
        setNativeStructuredOutput(true);
        setNativeStructuredOutputWithTools(true);

        HttpTransport transport =
                httpTransport != null ? httpTransport : HttpTransportFactory.getDefault();
        this.httpClient = new OllamaHttpClient(transport, baseUrl);
    }

    /**
     * Creates a new OllamaChatModel with default settings.
     *
     * @param modelName The name of the model to use.
     * @param baseUrl The base URL of the Ollama server.
     * @param defaultOptions Default configuration options.
     */
    public OllamaChatModel(String modelName, String baseUrl, OllamaOptions defaultOptions) {
        this(modelName, baseUrl, defaultOptions, null, null);
    }

    /**
     * Creates a new builder for OllamaChatModel.
     *
     * @return A new Builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String getModelName() {
        return this.modelName;
    }

    @Override
    public boolean supportsToolChoiceSpecific() {
        return true;
    }

    public boolean isStreaming() {
        return this.stream;
    }

    /**
     * Chat with the model using Ollama-specific options.
     * <p>
     * This method uses Ollama-specific options directly without conversion.
     *
     * @param messages The messages to send.
     * @param options The Ollama-specific options.
     * @return The chat response.
     */
    public ChatResponse chat(List<Msg> messages, OllamaOptions options) {
        return streamWithHttpClient(messages, null, null, options, false).blockLast();
    }

    /**
     * Chat with the model using default options.
     *
     * @param messages The messages to send.
     * @param options The generation options.
     * @return The chat response.
     */
    public ChatResponse chat(List<Msg> messages, GenerateOptions options) {
        return this.chat(messages, OllamaOptions.fromGenerateOptions(options));
    }

    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        boolean effectiveStream =
                options != null && options.getStream() != null ? options.getStream() : this.stream;
        return streamWithHttpClient(
                messages,
                tools,
                options != null ? options.getToolChoice() : null,
                OllamaOptions.fromGenerateOptions(options),
                effectiveStream);
    }

    /**
     * Shared logic for streaming and non-streaming requests.
     */
    private Flux<ChatResponse> streamWithHttpClient(
            List<Msg> messages,
            List<ToolSchema> tools,
            ToolChoice toolChoice,
            OllamaOptions options,
            boolean stream) {

        OllamaOptions mergedOptions = this.defaultOptions.merge(options);
        return Flux.defer(
                () -> {
                    List<OllamaMessage> formattedMessages = formatter.format(messages);
                    OllamaRequest request =
                            buildRequest(formattedMessages, tools, toolChoice, options, stream);
                    if (request.getFormat() == null
                            || tools == null
                            || tools.isEmpty()
                            || !supportsNativeStructuredOutput()
                            || !supportsNativeStructuredOutputWithTools()) {
                        return executeRequest(request, mergedOptions, stream);
                    }

                    // Some Ollama models suppress tool calls when format is present. Let the
                    // ordinary ReAct loop finish tool execution before constraining the answer.
                    request.setFormat(null);
                    return executeRequest(request, mergedOptions, stream)
                            .collectList()
                            .flatMapMany(
                                    draft ->
                                            finishToolRound(
                                                    draft,
                                                    formattedMessages,
                                                    options,
                                                    mergedOptions,
                                                    stream));
                });
    }

    private Flux<ChatResponse> finishToolRound(
            List<ChatResponse> draft,
            List<OllamaMessage> messages,
            OllamaOptions options,
            OllamaOptions mergedOptions,
            boolean stream) {
        if (draft.stream()
                .flatMap(r -> r.getContent().stream())
                .anyMatch(ToolUseBlock.class::isInstance)) {
            return Flux.fromIterable(draft);
        }
        String text =
                draft.stream()
                        .flatMap(r -> r.getContent().stream())
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::getText)
                        .collect(Collectors.joining());
        if (text.isBlank()) {
            return Flux.error(new IllegalStateException("Ollama returned no answer or tool calls"));
        }
        List<OllamaMessage> finalMessages = new ArrayList<>(messages);
        finalMessages.add(new OllamaMessage("assistant", text));
        finalMessages.add(
                new OllamaMessage(
                        "user",
                        "Return the final answer as JSON matching the supplied schema. Use the tool"
                                + " results in the conversation and preserve their values."));
        OllamaRequest finalRequest = buildRequest(finalMessages, null, null, options, stream);
        finalRequest.setTools(null);
        finalRequest.setToolChoice(null);
        ChatUsage draftUsage = null;
        for (ChatResponse response : draft) {
            if (response.getUsage() != null) {
                draftUsage = response.getUsage();
            }
        }
        ChatUsage hiddenUsage = draftUsage;
        boolean[] hasText = {false};
        return executeRequest(finalRequest, mergedOptions, stream)
                .doOnNext(
                        response -> {
                            if (response.getContent().stream()
                                    .anyMatch(ToolUseBlock.class::isInstance)) {
                                throw new IllegalStateException(
                                        "Ollama returned tool calls during final formatting");
                            }
                            hasText[0] |=
                                    response.getContent().stream()
                                            .filter(TextBlock.class::isInstance)
                                            .map(TextBlock.class::cast)
                                            .anyMatch(block -> !block.getText().isBlank());
                        })
                .map(response -> addHiddenUsage(response, hiddenUsage))
                .concatWith(
                        Flux.defer(
                                () ->
                                        hasText[0]
                                                ? Flux.empty()
                                                : Flux.error(
                                                        new IllegalStateException(
                                                                "Ollama returned no structured"
                                                                        + " response"))));
    }

    private static ChatResponse addHiddenUsage(ChatResponse response, ChatUsage hidden) {
        ChatUsage usage = response.getUsage();
        if (hidden == null
                || usage == null
                || (!Boolean.TRUE.equals(response.getMetadata().get("done"))
                        && response.getFinishReason() == null)) {
            return response;
        }
        ChatUsage combined =
                new ChatUsage(
                        hidden.getInputTokens() + usage.getInputTokens(),
                        hidden.getOutputTokens() + usage.getOutputTokens(),
                        hidden.getCachedTokens() + usage.getCachedTokens(),
                        hidden.getCacheCreationTokens() + usage.getCacheCreationTokens(),
                        hidden.getReasoningTokens() + usage.getReasoningTokens(),
                        hidden.getToolUsePromptTokens() + usage.getToolUsePromptTokens(),
                        hidden.getTime() + usage.getTime());
        return new ChatResponse(
                response.getId(),
                response.getContent(),
                combined,
                response.getMetadata(),
                response.getFinishReason());
    }

    private OllamaRequest buildRequest(
            List<OllamaMessage> formattedMessages,
            List<ToolSchema> tools,
            ToolChoice toolChoice,
            OllamaOptions options,
            boolean stream) {
        OllamaRequest request;
        if (formatter instanceof OllamaChatFormatter chatFormatter) {
            request =
                    chatFormatter.buildRequest(
                            modelName,
                            formattedMessages,
                            stream,
                            options,
                            defaultOptions,
                            tools,
                            toolChoice);
        } else if (formatter instanceof OllamaMultiAgentFormatter multiAgentFormatter) {
            request =
                    multiAgentFormatter.buildRequest(
                            modelName,
                            formattedMessages,
                            stream,
                            options,
                            defaultOptions,
                            tools,
                            toolChoice);
        } else {
            throw new IllegalStateException(
                    "Unsupported formatter type: " + formatter.getClass().getName());
        }

        return request;
    }

    private Flux<ChatResponse> executeRequest(
            OllamaRequest request, OllamaOptions mergedOptions, boolean stream) {
        Flux<ChatResponse> responseFlux;
        if (stream) {
            responseFlux =
                    Flux.defer(
                            () -> {
                                final String[] finalStableId = new String[1];

                                return httpClient.stream(request)
                                        .map(
                                                response -> {
                                                    ChatResponse parsedResponse =
                                                            formatter.parseResponse(
                                                                    response, Instant.now());

                                                    if (finalStableId[0] == null) {
                                                        finalStableId[0] = parsedResponse.getId();
                                                        return parsedResponse;
                                                    }

                                                    return parsedResponse.withId(finalStableId[0]);
                                                });
                            });
        } else {
            responseFlux =
                    Flux.defer(
                                    () -> {
                                        Instant startTime = Instant.now();
                                        try {
                                            OllamaResponse response = httpClient.chat(request);
                                            return Flux.just(
                                                    formatter.parseResponse(response, startTime));
                                        } catch (Exception e) {
                                            return Flux.error(e);
                                        }
                                    })
                            .subscribeOn(Schedulers.boundedElastic());
        }

        return responseFlux.transform(
                flux ->
                        ModelUtils.applyTimeoutAndRetry(
                                flux,
                                mergedOptions.toGenerateOptions(),
                                defaultOptions.toGenerateOptions(),
                                modelName,
                                "ollama"));
    }

    public static class Builder {
        private String modelName;
        private String baseUrl;
        private OllamaOptions defaultOptions;
        private Formatter<OllamaMessage, OllamaResponse, OllamaRequest> formatter;
        private HttpTransport httpTransport;
        private ProxyConfig proxyConfig;
        private int contextWindowSize = -1;
        private boolean stream = true;
        private boolean nativeStructuredOutput = true;
        private boolean nativeStructuredOutputWithTools = true;

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets whether streaming should be enabled.
         * <p>Default is {@code true}
         */
        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder defaultOptions(OllamaOptions defaultOptions) {
            this.defaultOptions = defaultOptions;
            return this;
        }

        public Builder formatter(
                Formatter<OllamaMessage, OllamaResponse, OllamaRequest> formatter) {
            this.formatter = formatter;
            return this;
        }

        /**
         * Sets the HTTP transport to use.
         *
         * <p><b>Note on proxy configuration:</b> Because {@code httpTransport} is a
         * fully-constructed HTTP client instance, its configuration (including proxy)
         * cannot be modified after creation. If you need to configure a proxy, either:
         *
         * <ul>
         *   <li>Use {@link #proxy(ProxyConfig)} without calling {@code httpTransport()},
         *       for simple proxy-only customization.
         *   <li>Configure the proxy directly within the transport's
         *       {@link HttpTransportConfig} when building the transport instance.
         * </ul>
         *
         * <p>If both {@code httpTransport()} and {@code proxy()} are called,
         * {@code httpTransport()} takes full precedence and {@code proxy()} is ignored
         * (a warning is logged at build time).
         *
         * @param httpTransport the HTTP transport (null for default from factory)
         * @return this builder instance
         */
        public Builder httpTransport(HttpTransport httpTransport) {
            this.httpTransport = httpTransport;
            return this;
        }

        /**
         * Sets the proxy configuration for HTTP traffic.
         *
         * <p><b>Interaction with {@link #httpTransport(HttpTransport)}:</b>
         * Because {@code httpTransport} is a fully-constructed HTTP client instance,
         * its configuration cannot be modified after creation. The final transport is
         * determined as follows:
         *
         * <ul>
         *   <li>If only {@code proxy()} is called: the default {@link OkHttpTransport}
         *       is used with the specified proxy configuration applied.
         *   <li>If only {@code httpTransport()} is called: the provided transport is
         *       used as-is (proxy must be configured within the transport's own
         *       {@link HttpTransportConfig}).
         *   <li>If <i>both</i> are called: {@code httpTransport()} takes full precedence.
         *       The {@code proxy()} setting is <b>ignored</b> and a warning is logged.
         *       To configure proxy with a custom transport, set it within the transport's
         *       {@link HttpTransportConfig} directly.
         *   <li>If neither is called: the default transport from {@link HttpTransportFactory}
         *       is used (no proxy).
         * </ul>
         *
         * @param proxyConfig the proxy configuration (see {@link ProxyConfig})
         * @return this builder instance
         */
        public Builder proxy(ProxyConfig proxyConfig) {
            this.proxyConfig = proxyConfig;
            return this;
        }

        public Builder contextWindowSize(int contextWindowSize) {
            this.contextWindowSize = contextWindowSize;
            return this;
        }

        /**
         * Sets whether this model supports native structured output via Ollama's
         * {@code format} field.
         *
         * <p>Defaults to {@code true}. Set to {@code false} for endpoints that do not
         * support JSON Schema-constrained output; the agent will use the synthetic
         * {@code generate_response} tool instead.
         *
         * @param nativeStructuredOutput false to disable native structured output
         * @return this builder instance
         */
        public Builder nativeStructuredOutput(boolean nativeStructuredOutput) {
            this.nativeStructuredOutput = nativeStructuredOutput;
            return this;
        }

        /**
         * Sets whether this model handles native structured output alongside tool calling.
         *
         * <p>Defaults to {@code true}. Tool rounds run without {@code format}; once the model
         * stops calling tools, a separate request without tools produces the structured answer.
         * Set to {@code false} to use the synthetic {@code generate_response} tool instead.
         *
         * @param nativeStructuredOutputWithTools true to use native output with tools
         * @return this builder instance
         */
        public Builder nativeStructuredOutputWithTools(boolean nativeStructuredOutputWithTools) {
            this.nativeStructuredOutputWithTools = nativeStructuredOutputWithTools;
            return this;
        }

        public OllamaChatModel build() {
            OllamaOptions finalOptions =
                    defaultOptions != null
                            ? defaultOptions.copy()
                            : OllamaOptions.builder().build();

            ExecutionConfig mergedConfig =
                    ExecutionConfig.mergeConfigs(
                            finalOptions.getExecutionConfig(), ExecutionConfig.MODEL_DEFAULTS);

            finalOptions.setExecutionConfig(mergedConfig);

            HttpTransport transport = resolveTransport();

            OllamaChatModel model =
                    new OllamaChatModel(
                            modelName, baseUrl, finalOptions, formatter, transport, stream);
            model.setNativeStructuredOutput(nativeStructuredOutput);
            model.setNativeStructuredOutputWithTools(
                    nativeStructuredOutput && nativeStructuredOutputWithTools);
            if (contextWindowSize >= 0) {
                model.setContextWindowSize(contextWindowSize);
            }
            return model;
        }

        /**
         * Resolves the final HttpTransport to use.
         *
         * <p>If {@code httpTransport()} is set, it takes full precedence and
         * {@code proxyConfig} is ignored (with a warning logged). Otherwise,
         * if {@code proxy()} is set, a default transport with the proxy applied
         * is created. If neither is set, the factory default is used.
         *
         * @return the resolved HttpTransport
         */
        private HttpTransport resolveTransport() {
            if (httpTransport != null) {
                if (proxyConfig != null) {
                    log.warn(
                            "OllamaChatModel: both proxy() and httpTransport() are set. "
                                    + "httpTransport() takes precedence, proxy() is ignored. "
                                    + "To configure proxy, use one of the following:\n"
                                    + "  1. Simple: only call proxy() without httpTransport()\n"
                                    + "  2. Advanced: set proxy in HttpTransportConfig when "
                                    + "building a custom HttpTransport");
                }
                return httpTransport;
            }

            if (proxyConfig != null) {
                // Only proxy() called → use default transport with proxy
                return OkHttpTransport.builder()
                        .config(HttpTransportConfig.builder().proxy(proxyConfig).build())
                        .build();
            }

            // Neither called → use factory default
            return HttpTransportFactory.getDefault();
        }
    }
}
