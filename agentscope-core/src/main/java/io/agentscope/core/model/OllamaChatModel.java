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
package io.agentscope.core.model;

import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.formatter.ollama.OllamaChatFormatter;
import io.agentscope.core.formatter.ollama.OllamaMultiAgentFormatter;
import io.agentscope.core.formatter.ollama.dto.OllamaMessage;
import io.agentscope.core.formatter.ollama.dto.OllamaRequest;
import io.agentscope.core.formatter.ollama.dto.OllamaResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ollama.OllamaOptions;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import java.time.Instant;
import java.util.List;
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
    private final OllamaHttpClient httpClient;
    private final OllamaOptions defaultOptions;
    private final Formatter<OllamaMessage, OllamaResponse, OllamaRequest> formatter;

    /**
     * Creates a new OllamaChatModel.
     *
     * @param modelName The name of the model to use (e.g., "llama2", "mistral").
     * @param baseUrl The base URL of the Ollama server (e.g., "http://localhost:11434").
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
        this.modelName = modelName;
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : OllamaOptions.builder().build();
        this.formatter = formatter != null ? formatter : new OllamaChatFormatter();

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
        return streamWithHttpClient(
                messages,
                tools,
                options.getToolChoice(),
                OllamaOptions.fromGenerateOptions(options),
                true);
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

        // Calculate merged options for ModelUtils.applyTimeoutAndRetry
        OllamaOptions mergedOptions = this.defaultOptions.merge(options);

        List<OllamaMessage> formattedMessages = formatter.format(messages);

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

        Flux<ChatResponse> responseFlux;
        if (stream) {
            responseFlux =
                    httpClient.stream(request)
                            .map(response -> formatter.parseResponse(response, Instant.now()));
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

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
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

        public Builder httpTransport(HttpTransport httpTransport) {
            this.httpTransport = httpTransport;
            return this;
        }

        public OllamaChatModel build() {
            // 1. Get base OllamaOptions (copy if exists, or create new)
            OllamaOptions finalOptions =
                    defaultOptions != null
                            ? defaultOptions.copy()
                            : OllamaOptions.builder().build();

            // 2. Merge ExecutionConfig directly
            // Priority: user config > MODEL_DEFAULTS
            ExecutionConfig mergedConfig =
                    ExecutionConfig.mergeConfigs(
                            finalOptions.getExecutionConfig(), ExecutionConfig.MODEL_DEFAULTS);

            // 3. Set merged config back
            finalOptions.setExecutionConfig(mergedConfig);

            return new OllamaChatModel(modelName, baseUrl, finalOptions, formatter, httpTransport);
        }
    }
}
