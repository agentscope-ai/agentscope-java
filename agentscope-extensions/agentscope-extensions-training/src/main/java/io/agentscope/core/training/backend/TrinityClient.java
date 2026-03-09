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

package io.agentscope.core.training.backend;

import io.agentscope.core.training.backend.dto.CommitRequest;
import io.agentscope.core.training.backend.dto.FeedbackRequest;
import io.agentscope.core.training.backend.dto.StatusResponse;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Trinity Backend Client
 *
 * <p>Simplified Trinity client that only encapsulates Feedback and Commit APIs.
 *
 * <p>Chat API is no longer called through this client, but uses AgentScope's {@link io.agentscope.core.model.OpenAIChatModel} directly,
 * because Trinity's Chat API is fully compatible with OpenAI format.
 *
 * <p>This client is only responsible for Trinity-specific training APIs:
 * <ul>
 *   <li>Feedback API - Submit reward feedback</li>
 *   <li>Commit API - Trigger training commit</li>
 * </ul>
 */
public class TrinityClient {
    private static final Logger logger = LoggerFactory.getLogger(TrinityClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String baseUrl;
    private final OkHttpClient httpClient;

    private TrinityClient(Builder builder) {
        this.baseUrl = builder.endpoint;
        this.httpClient =
                new OkHttpClient.Builder()
                        .connectTimeout(builder.timeout)
                        .readTimeout(builder.timeout)
                        .writeTimeout(builder.timeout)
                        .build();
    }

    /**
     * Submit Feedback (reward feedback)
     *
     * @param request Feedback request (containing msg_ids and reward)
     * @return Mono&lt;Void&gt; that completes when feedback is submitted
     */
    public Mono<Void> feedback(FeedbackRequest request) {
        return Mono.fromCallable(
                        () -> {
                            logger.debug(
                                    "Submitting feedback: msgIds={}, reward={}, taskId={},"
                                            + " runId={}",
                                    request.getMsgIds(),
                                    request.getReward(),
                                    request.getTaskId(),
                                    request.getRunId());

                            String jsonBody = JsonUtils.getJsonCodec().toJson(request);
                            String endpoint = baseUrl + "/feedback";

                            // Print actual JSON sent for debugging
                            logger.info("Sending feedback to {}: {}", endpoint, jsonBody);

                            Request httpRequest =
                                    new Request.Builder()
                                            .url(endpoint)
                                            .post(RequestBody.create(jsonBody, JSON))
                                            .build();

                            try (Response response = httpClient.newCall(httpRequest).execute()) {
                                if (!response.isSuccessful()) {
                                    throw new RuntimeException(
                                            "Feedback API failed: " + response.code());
                                }

                                String responseBody = response.body().string();
                                StatusResponse statusResponse =
                                        JsonUtils.getJsonCodec()
                                                .fromJson(responseBody, StatusResponse.class);

                                logger.info(
                                        "Feedback submitted: msgIds={}, reward={}, taskId={},"
                                                + " runId={}",
                                        request.getMsgIds(),
                                        request.getReward(),
                                        request.getTaskId(),
                                        request.getRunId());

                                return statusResponse;
                            }
                        })
                .doOnError(e -> logger.error("Failed to submit feedback: {}", e.getMessage()))
                .then();
    }

    /**
     * Submit Commit to trigger training
     *
     * @param request Commit request (containing task_id and run_id)
     * @return Mono&lt;Void&gt; that completes when commit is successful
     */
    public Mono<Void> commit(CommitRequest request) {
        return Mono.fromCallable(
                        () -> {
                            logger.debug(
                                    "Triggering commit: taskId={}, runId={}",
                                    request.getTaskId(),
                                    request.getRunId());

                            String jsonBody = JsonUtils.getJsonCodec().toJson(request);
                            String endpoint = baseUrl + "/commit";

                            Request httpRequest =
                                    new Request.Builder()
                                            .url(endpoint)
                                            .post(RequestBody.create(jsonBody, JSON))
                                            .build();

                            try (Response response = httpClient.newCall(httpRequest).execute()) {
                                if (!response.isSuccessful()) {
                                    throw new RuntimeException(
                                            "Commit API failed: " + response.code());
                                }

                                String responseBody = response.body().string();
                                StatusResponse statusResponse =
                                        JsonUtils.getJsonCodec()
                                                .fromJson(responseBody, StatusResponse.class);

                                logger.info(
                                        "Training committed: taskId={}, runId={}, timeThreshold={}",
                                        request.getTaskId(),
                                        request.getRunId(),
                                        request.getTimeThreshold());

                                return statusResponse;
                            }
                        })
                .doOnError(e -> logger.error("Failed to commit: {}", e.getMessage()))
                .then();
    }

    public String getEndpoint() {
        return baseUrl;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String endpoint;
        private Duration timeout = Duration.ofSeconds(300);

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public TrinityClient build() {
            if (endpoint == null || endpoint.isEmpty()) {
                throw new IllegalArgumentException("endpoint is required");
            }
            return new TrinityClient(this);
        }
    }
}
