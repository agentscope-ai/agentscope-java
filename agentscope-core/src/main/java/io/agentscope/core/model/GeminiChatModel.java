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
import io.agentscope.core.formatter.gemini.GeminiChatFormatter;
import io.agentscope.core.formatter.gemini.dto.GeminiContent;
import io.agentscope.core.formatter.gemini.dto.GeminiRequest;
import io.agentscope.core.formatter.gemini.dto.GeminiResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Gemini Chat Model implementation using the official Google GenAI Java SDK.
 *
 * <p>
 * This implementation provides complete integration with Gemini's Content
 * Generation API,
 * including tool calling and multi-agent conversation support.
 *
 * <p>
 * <b>Supported Features:</b>
 * <ul>
 * <li>Text generation with streaming and non-streaming modes</li>
 * <li>Tool/function calling support</li>
 * <li>Multi-agent conversation with history merging</li>
 * <li>Vision capabilities (images, audio, video)</li>
 * <li>Thinking mode (extended reasoning)</li>
 * </ul>
 */
public class GeminiChatModel extends ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(GeminiChatModel.class);
    private static final String DEFAULT_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private final String baseUrl;
    private final String apiKey;
    private final String accessToken;
    private final String modelName;
    private final boolean streamEnabled;
    private final GenerateOptions defaultOptions;
    private final Formatter<GeminiContent, GeminiResponse, GeminiRequest> formatter;
    private final OkHttpClient httpClient;
    private final JsonCodec jsonCodec;
    private final GeminiStructuredOutputHandler structuredOutputHandler =
            new GeminiStructuredOutputHandler();
    private final GeminiThinkingPolicy thinkingPolicy = new GeminiThinkingPolicy();
    private final GeminiTransport transport;
    private final GeminiRequestAssembler requestAssembler;
    private final GeminiResponseGuard responseGuard;
    private final GeminiRetryPolicy retryPolicy;

    /**
     * Creates a new Gemini chat model instance.
     *
     * @param baseUrl        the base URL for the API (optional)
     * @param apiKey         the API key for Gemini API (optional if accessToken
     *                       provided)
     * @param accessToken    the access token for Vertex AI (optional)
     * @param modelName      the model name (e.g., "gemini-2.0-flash")
     * @param streamEnabled  whether streaming should be enabled
     * @param defaultOptions default generation options
     * @param formatter      the message formatter to use
     * @param timeout        read/connect timeout in seconds (default: 60)
     * @param client         optional custom OkHttpClient
     */
    public GeminiChatModel(
            String baseUrl,
            String apiKey,
            String accessToken,
            String modelName,
            boolean streamEnabled,
            GenerateOptions defaultOptions,
            Formatter<GeminiContent, GeminiResponse, GeminiRequest> formatter,
            Long timeout,
            OkHttpClient client) {
        if (apiKey == null && accessToken == null) {
            throw new IllegalArgumentException("Either API Key or Access Token must be provided");
        }
        this.baseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URL;
        this.apiKey = apiKey;
        this.accessToken = accessToken;
        this.modelName = Objects.requireNonNull(modelName, "Model name is required");
        this.streamEnabled = streamEnabled;
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : GenerateOptions.builder().build();
        this.formatter = formatter != null ? formatter : new GeminiChatFormatter();

        if (client != null) {
            this.httpClient = client;
        } else {
            long timeoutVal = timeout != null ? timeout : 60L;
            this.httpClient =
                    new OkHttpClient.Builder()
                            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                            .connectTimeout(timeoutVal, TimeUnit.SECONDS)
                            .readTimeout(timeoutVal, TimeUnit.SECONDS)
                            .writeTimeout(timeoutVal, TimeUnit.SECONDS)
                            .build();
        }

        this.jsonCodec = JsonUtils.getJsonCodec();
        this.transport = new GeminiTransport(this.httpClient, this.jsonCodec, this.formatter);
        this.requestAssembler =
                new GeminiRequestAssembler(
                        this.baseUrl,
                        this.modelName,
                        this.streamEnabled,
                        this.apiKey,
                        this.accessToken,
                        this.defaultOptions,
                        this.formatter,
                        this.jsonCodec,
                        this.thinkingPolicy,
                        log);
        this.responseGuard = new GeminiResponseGuard(this.modelName, log);
        this.retryPolicy = new GeminiRetryPolicy(this.modelName, log);
    }

    /**
     * Stream chat completion responses from Gemini's API.
     *
     * @param messages AgentScope messages to send to the model
     * @param tools    Optional list of tool schemas
     * @param options  Optional generation options
     * @return Flux stream of chat responses
     */
    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Instant startTime = Instant.now();
        log.debug(
                "Gemini stream: model={}, messages={}, tools_present={}, streaming={}",
                modelName,
                messages != null ? messages.size() : 0,
                tools != null && !tools.isEmpty(),
                streamEnabled);

        return Flux.defer(
                        () -> {
                            try {
                                GeminiRequestAssembler.PreparedRequest preparedRequest =
                                        requestAssembler.assemble(messages, tools, options);

                                // 4. Send Request and Handle Response
                                Flux<ChatResponse> responseFlux;
                                if (preparedRequest.isStreamForRequest()) {
                                    responseFlux =
                                            transport.handleStreamResponse(
                                                    preparedRequest.getHttpRequest(), startTime);
                                } else {
                                    responseFlux =
                                            transport.handleUnaryResponse(
                                                    preparedRequest.getHttpRequest(), startTime);
                                }

                                // 5. Add fallback for structured output (JSON text -> Tool Call)
                                return responseFlux.map(
                                        response ->
                                                responseGuard.ensureMeaningfulContent(
                                                        structuredOutputHandler
                                                                .ensureStructuredOutputMetadata(
                                                                        structuredOutputHandler
                                                                                .fixStructuredOutputResponse(
                                                                                        response,
                                                                                        options,
                                                                                        tools))));

                            } catch (Exception e) {
                                log.error(
                                        "Failed to prepare Gemini request: {}", e.getMessage(), e);
                                return Flux.error(
                                        new ModelException(
                                                "Failed to prepare Gemini request: "
                                                        + e.getMessage(),
                                                e));
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .retryWhen(retryPolicy.build());
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * Close the HTTP client resources if needed.
     */
    public void close() {
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }

    /**
     * Creates a new builder for GeminiChatModel.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating GeminiChatModel instances.
     */
    public static class Builder {
        private String baseUrl;
        private String apiKey;
        private String accessToken;
        private String modelName = "gemini-2.5-flash";
        private boolean streamEnabled = true;
        private GenerateOptions defaultOptions;
        private Formatter<GeminiContent, GeminiResponse, GeminiRequest> formatter;
        private Long timeout;
        private OkHttpClient httpClient;

        private List<Protocol> protocols = Collections.singletonList(Protocol.HTTP_1_1);
        private String project;
        private String location;
        private Boolean vertexAI;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder accessToken(String accessToken) {
            this.accessToken = accessToken;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder streamEnabled(boolean streamEnabled) {
            this.streamEnabled = streamEnabled;
            return this;
        }

        public Builder defaultOptions(GenerateOptions defaultOptions) {
            this.defaultOptions = defaultOptions;
            return this;
        }

        public Builder formatter(
                Formatter<GeminiContent, GeminiResponse, GeminiRequest> formatter) {
            this.formatter = formatter;
            return this;
        }

        public Builder timeout(Long timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder httpClient(OkHttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public Builder protocols(List<Protocol> protocols) {
            this.protocols = protocols;
            return this;
        }

        public Builder project(String project) {
            this.project = project;
            return this;
        }

        public Builder location(String location) {
            this.location = location;
            return this;
        }

        public Builder vertexAI(Boolean vertexAI) {
            this.vertexAI = vertexAI;
            return this;
        }

        public GeminiChatModel build() {
            OkHttpClient client = this.httpClient;
            if (client == null) {
                long timeoutVal = this.timeout != null ? this.timeout : 60L;
                OkHttpClient.Builder clientBuilder =
                        new OkHttpClient.Builder()
                                .connectTimeout(timeoutVal, TimeUnit.SECONDS)
                                .readTimeout(timeoutVal, TimeUnit.SECONDS)
                                .writeTimeout(timeoutVal, TimeUnit.SECONDS);

                if (this.protocols != null) {
                    clientBuilder.protocols(this.protocols);
                }
                client = clientBuilder.build();
            }

            // Construct Vertex AI Base URL if needed
            String finalBaseUrl = this.baseUrl;
            if (finalBaseUrl == null
                    && (Boolean.TRUE.equals(this.vertexAI)
                            || (this.project != null && !this.project.isEmpty()))) {
                String loc =
                        this.location != null && !this.location.isEmpty()
                                ? this.location
                                : "us-central1";
                if (this.project == null || this.project.isEmpty()) {
                    throw new IllegalArgumentException("Project ID is required for Vertex AI");
                }
                finalBaseUrl =
                        String.format(
                                "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/",
                                loc, this.project, loc);
            }

            return new GeminiChatModel(
                    finalBaseUrl,
                    apiKey,
                    accessToken,
                    modelName,
                    streamEnabled,
                    defaultOptions,
                    formatter,
                    timeout,
                    client);
        }
    }
}
