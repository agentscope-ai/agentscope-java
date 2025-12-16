/*
 * Copyright 2024-2025 the original author or authors.
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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.formatter.gemini.GeminiChatFormatter;
import io.agentscope.core.formatter.gemini.dto.GeminiContent;
import io.agentscope.core.formatter.gemini.dto.GeminiRequest;
import io.agentscope.core.formatter.gemini.dto.GeminiResponse;
import io.agentscope.core.message.Msg;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Gemini Chat Model implementation using OkHttp for direct API calls.
 *
 * <p>
 * This implementation replaces the Google GenAI SDK with direct HTTP requests
 * to the Gemini API, providing standard AgentScope integration.
 *
 * <p>
 * <b>Supported Features:</b>
 * <ul>
 * <li>Text generation with streaming (SSE) and non-streaming modes</li>
 * <li>Tool/function calling support through DTOs</li>
 * <li>Multi-agent conversation support</li>
 * </ul>
 */
public class GeminiChatModel extends ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(GeminiChatModel.class);
    private static final String BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String apiKey;
    private final String modelName;
    private final boolean streamEnabled;
    private final GenerateOptions defaultOptions;
    private final Formatter<GeminiContent, GeminiResponse, GeminiRequest> formatter;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new Gemini chat model instance.
     *
     * @param apiKey         the API key for Gemini API
     * @param modelName      the model name (e.g., "gemini-2.0-flash")
     * @param streamEnabled  whether streaming should be enabled
     * @param defaultOptions default generation options
     * @param formatter      the message formatter to use
     * @param timeout        read/connect timeout in seconds (default: 60)
     */
    public GeminiChatModel(
            String apiKey,
            String modelName,
            boolean streamEnabled,
            GenerateOptions defaultOptions,
            Formatter<GeminiContent, GeminiResponse, GeminiRequest> formatter,
            Long timeout,
            OkHttpClient client) {
        this.apiKey = Objects.requireNonNull(apiKey, "API Key is required");
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
                            .connectTimeout(timeoutVal, TimeUnit.SECONDS)
                            .readTimeout(timeoutVal, TimeUnit.SECONDS)
                            .writeTimeout(timeoutVal, TimeUnit.SECONDS)
                            .build();
        }

        this.objectMapper =
                new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
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
                                // 1. Prepare Request DTO
                                GeminiRequest requestDto = new GeminiRequest();

                                // Format messages
                                List<GeminiContent> contents = formatter.format(messages);
                                requestDto.setContents(contents);

                                // Apply options, tools, tool choice
                                formatter.applyOptions(requestDto, options, defaultOptions);

                                if (tools != null && !tools.isEmpty()) {
                                    formatter.applyTools(requestDto, tools);
                                    if (options != null && options.getToolChoice() != null) {
                                        formatter.applyToolChoice(
                                                requestDto, options.getToolChoice());
                                    }
                                }

                                // 2. Serialize Request
                                String requestJson = objectMapper.writeValueAsString(requestDto);
                                log.trace("Gemini Request JSON: {}", requestJson);

                                // 3. Build HTTP Request
                                String endpoint =
                                        streamEnabled
                                                ? ":streamGenerateContent"
                                                : ":generateContent";
                                String url = BASE_URL + modelName + endpoint + "?key=" + apiKey;

                                if (streamEnabled) {
                                    url += "&alt=sse";
                                }

                                Request httpRequest =
                                        new Request.Builder()
                                                .url(url)
                                                .post(RequestBody.create(requestJson, JSON))
                                                .build();

                                // 4. Send Request and Handle Response
                                if (streamEnabled) {
                                    return handleStreamResponse(httpRequest, startTime);
                                } else {
                                    return handleUnaryResponse(httpRequest, startTime);
                                }

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
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<ChatResponse> handleUnaryResponse(Request request, Instant startTime) {
        try {
            Response response = httpClient.newCall(request).execute();
            try (ResponseBody responseBody = response.body()) {
                String bodyString = responseBody != null ? responseBody.string() : null;
                if (!response.isSuccessful() || bodyString == null) {
                    String errorBody = bodyString != null ? bodyString : "null";
                    throw new IOException(
                            "Gemini API Error: " + response.code() + " - " + errorBody);
                }

                GeminiResponse geminiResponse =
                        objectMapper.readValue(bodyString, GeminiResponse.class);
                ChatResponse chatResponse = formatter.parseResponse(geminiResponse, startTime);
                return Flux.just(chatResponse);
            }
        } catch (IOException e) {
            return Flux.error(new ModelException("Gemini network error: " + e.getMessage(), e));
        }
    }

    private Flux<ChatResponse> handleStreamResponse(Request request, Instant startTime) {
        return Flux.create(
                sink -> {
                    // Use try-with-resources to manage Response and response body stream
                    try (Response response = httpClient.newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                            try (ResponseBody body = response.body()) {
                                String error = body != null ? body.string() : "Unknown error";
                                sink.error(
                                        new IOException(
                                                "Gemini API Error: "
                                                        + response.code()
                                                        + " - "
                                                        + error));
                            }
                            return;
                        }

                        ResponseBody responseBody = response.body();
                        if (responseBody == null) {
                            sink.error(new IOException("Empty response body"));
                            return;
                        }

                        // Reading the stream
                        try (BufferedReader reader =
                                new BufferedReader(
                                        new InputStreamReader(
                                                responseBody.byteStream(),
                                                StandardCharsets.UTF_8))) {

                            String line;
                            while (!sink.isCancelled() && (line = reader.readLine()) != null) {
                                if (line.startsWith("data: ")) {
                                    String json =
                                            line.substring(6).trim(); // Remove "data: " prefix
                                    if (!json.isEmpty()) {
                                        try {
                                            GeminiResponse geminiResponse =
                                                    objectMapper.readValue(
                                                            json, GeminiResponse.class);
                                            ChatResponse chatResponse =
                                                    formatter.parseResponse(
                                                            geminiResponse, startTime);
                                            sink.next(chatResponse);
                                        } catch (Exception e) {
                                            log.warn(
                                                    "Failed to parse Gemini stream chunk: {}",
                                                    e.getMessage());
                                        }
                                    }
                                }
                            }
                        }

                        if (!sink.isCancelled()) {
                            sink.complete();
                        }

                    } catch (Exception e) {
                        sink.error(new ModelException("Gemini stream error: " + e.getMessage(), e));
                    }
                });
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
        private String apiKey;
        private String modelName = "gemini-2.5-flash";
        private boolean streamEnabled = true;
        private GenerateOptions defaultOptions;
        private Formatter<GeminiContent, GeminiResponse, GeminiRequest> formatter;
        private Long timeout;
        private OkHttpClient httpClient;

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
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

        public GeminiChatModel build() {
            return new GeminiChatModel(
                    apiKey,
                    modelName,
                    streamEnabled,
                    defaultOptions,
                    formatter,
                    timeout,
                    httpClient);
        }
    }
}
