/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.studio.pojo.PushMessageRequest;
import io.agentscope.core.studio.pojo.RegisterRunRequest;
import io.agentscope.core.studio.pojo.RequestUserInputRequest;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * HTTP client for communicating with AgentScope Studio.
 *
 * <p>This client handles HTTP requests to Studio's REST API endpoints using OkHttp.
 * It provides methods for registering runs, pushing messages, and requesting user input.
 * All operations are reactive and return Mono types for integration with Project Reactor.
 *
 * <p>The client includes automatic retry logic for failed requests (configurable via
 * StudioConfig).
 */
public class StudioClient {
    private static final Logger logger = LoggerFactory.getLogger(StudioClient.class);
    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.get("application/json; charset=utf-8");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final OkHttpClient httpClient;
    private final StudioConfig config;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    /**
     * Creates a new Studio HTTP client.
     *
     * @param config Configuration for Studio connection
     */
    public StudioClient(StudioConfig config) {
        this.config = config;
        this.baseUrl = config.getStudioUrl();
        this.objectMapper = new ObjectMapper();
        this.httpClient =
                new OkHttpClient.Builder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .readTimeout(Duration.ofSeconds(30))
                        .writeTimeout(Duration.ofSeconds(30))
                        .build();
    }

    /**
     * Registers a new run with Studio.
     *
     * <p>This must be called before sending any messages. It notifies Studio that a new agent
     * application is starting up.
     *
     * <p>POST /trpc/registerRun
     *
     * @return A Mono that completes when registration succeeds
     */
    public Mono<Void> registerRun() {
        return executeWithRetry(
                Mono.fromCallable(
                        () -> {
                            RegisterRunRequest payload =
                                    RegisterRunRequest.builder()
                                            .id(config.getRunId())
                                            .project(config.getProject())
                                            .name(config.getRunName())
                                            .timestamp(formatTimestamp(Instant.now()))
                                            .pid(ProcessHandle.current().pid())
                                            .status("running")
                                            .runDir("")
                                            .build();

                            String json = objectMapper.writeValueAsString(payload);
                            RequestBody body = RequestBody.create(json, JSON_MEDIA_TYPE);

                            Request request =
                                    new Request.Builder()
                                            .url(baseUrl + "/trpc/registerRun")
                                            .post(body)
                                            .build();

                            try (Response response = httpClient.newCall(request).execute()) {
                                if (!response.isSuccessful()) {
                                    throw new IOException(
                                            "Failed to register run: HTTP " + response.code());
                                }
                            }
                            return null;
                        }));
    }

    /**
     * Pushes a message to Studio for visualization.
     *
     * <p>This method is typically called automatically by StudioMessageHook after an agent
     * produces a response. It includes retry logic for robustness.
     *
     * <p>POST /trpc/pushMessage
     *
     * @param msg The message to push
     * @return A Mono that completes when the message is successfully pushed
     */
    public Mono<Void> pushMessage(Msg msg) {
        return executeWithRetry(
                Mono.fromCallable(
                        () -> {
                            String messageId = UUID.randomUUID().toString();

                            PushMessageRequest payload =
                                    PushMessageRequest.builder()
                                            .runId(config.getRunId())
                                            .replyId(messageId)
                                            .name(msg.getName() != null ? msg.getName() : messageId)
                                            .role(msg.getRole().name().toLowerCase())
                                            .msg(msg)
                                            .build();

                            String json = objectMapper.writeValueAsString(payload);
                            RequestBody body = RequestBody.create(json, JSON_MEDIA_TYPE);

                            Request request =
                                    new Request.Builder()
                                            .url(baseUrl + "/trpc/pushMessage")
                                            .post(body)
                                            .build();

                            try (Response response = httpClient.newCall(request).execute()) {
                                if (!response.isSuccessful()) {
                                    throw new IOException(
                                            "HTTP " + response.code() + ": " + response.message());
                                }
                            }
                            return null;
                        }));
    }

    /**
     * Requests user input from Studio web interface.
     *
     * <p>This sends a request to Studio, which displays an input form to the user in the web UI.
     * The actual input will be received via WebSocket (see StudioWebSocketClient).
     *
     * <p>POST /trpc/requestUserInput
     *
     * @param agentId ID of the agent requesting input
     * @param agentName Name of the agent requesting input
     * @param structuredSchema Optional JSON schema for structured input
     * @return A Mono containing the request ID (used to match WebSocket response)
     */
    public Mono<String> requestUserInput(
            String agentId, String agentName, Object structuredSchema) {
        return executeWithRetry(
                Mono.fromCallable(
                        () -> {
                            String requestId = UUID.randomUUID().toString();

                            RequestUserInputRequest payload =
                                    RequestUserInputRequest.builder()
                                            .requestId(requestId)
                                            .runId(config.getRunId())
                                            .agentId(agentId)
                                            .agentName(agentName)
                                            .structuredInput(structuredSchema)
                                            .build();

                            String json = objectMapper.writeValueAsString(payload);
                            RequestBody body = RequestBody.create(json, JSON_MEDIA_TYPE);

                            Request request =
                                    new Request.Builder()
                                            .url(baseUrl + "/trpc/requestUserInput")
                                            .post(body)
                                            .build();

                            try (Response response = httpClient.newCall(request).execute()) {
                                if (!response.isSuccessful()) {
                                    throw new IOException(
                                            "HTTP " + response.code() + ": " + response.message());
                                }
                                return requestId;
                            }
                        }));
    }

    /**
     * Shuts down the HTTP client and releases resources.
     */
    public void shutdown() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    /**
     * Executes a Studio API operation with automatic retry logic.
     *
     * @param operation The operation to execute
     * @param <T> Return type
     * @return A Mono with retry logic applied
     */
    private <T> Mono<T> executeWithRetry(Mono<T> operation) {
        return operation.retryWhen(
                Retry.backoff(config.getMaxRetries(), Duration.ofMillis(100))
                        .maxBackoff(Duration.ofSeconds(5))
                        .filter(throwable -> throwable instanceof IOException)
                        .doBeforeRetry(
                                signal ->
                                        logger.warn(
                                                "Retrying Studio API call, attempt: {}",
                                                signal.totalRetries() + 1)));
    }

    private String formatTimestamp(Instant instant) {
        return TIMESTAMP_FORMATTER.format(instant);
    }
}
