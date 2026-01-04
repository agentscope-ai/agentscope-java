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
package io.agentscope.core.memory.reme;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * HTTP client for interacting with the ReMe API.
 */
public class ReMeClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String SUMMARY_ENDPOINT = "/summary_personal_memory";
    private static final String RETRIEVE_ENDPOINT = "/retrieve_personal_memory";

    private final OkHttpClient httpClient;
    private final String apiBaseUrl;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new ReMeClient with specified configuration.
     *
     * @param apiBaseUrl The base URL of the ReMe API (e.g., "http://localhost:8002")
     */
    public ReMeClient(String apiBaseUrl) {
        this(apiBaseUrl, Duration.ofSeconds(60));
    }

    /**
     * Creates a new ReMeClient with custom timeout.
     *
     * @param apiBaseUrl The base URL of the ReMe API
     * @param timeout HTTP request timeout duration
     */
    public ReMeClient(String apiBaseUrl, Duration timeout) {
        this.apiBaseUrl =
                apiBaseUrl.endsWith("/")
                        ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1)
                        : apiBaseUrl;
        this.objectMapper = new ObjectMapper();
        this.httpClient =
                new OkHttpClient.Builder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .readTimeout(timeout)
                        .writeTimeout(Duration.ofSeconds(30))
                        .build();
    }

    /**
     * Executes a POST request to the ReMe API and parses the response.
     *
     * <p>This is a generic method that handles HTTP communication, JSON serialization,
     * error handling, and response parsing for all POST endpoints.
     *
     * @param endpoint The API endpoint path (e.g., "/summary_personal_memory")
     * @param request The request object to serialize as JSON
     * @param responseType The class of the response type
     * @param operationName A human-readable name for the operation (for error messages)
     * @param <T> The request type
     * @param <R> The response type
     * @return A Mono emitting the parsed response
     */
    private <T, R> Mono<R> executePost(
            String endpoint, T request, Class<R> responseType, String operationName) {
        return Mono.fromCallable(
                        () -> {
                            // Serialize request to JSON
                            String json = objectMapper.writeValueAsString(request);

                            // Build HTTP request
                            Request httpRequest =
                                    new Request.Builder()
                                            .url(apiBaseUrl + endpoint)
                                            .addHeader("Content-Type", "application/json")
                                            .post(RequestBody.create(json, JSON))
                                            .build();

                            // Execute request
                            try (Response response = httpClient.newCall(httpRequest).execute()) {
                                if (!response.isSuccessful()) {
                                    String errorBody =
                                            response.body() != null
                                                    ? response.body().string()
                                                    : "No error details";
                                    throw new IOException(
                                            "ReMe API "
                                                    + operationName
                                                    + " failed with status "
                                                    + response.code()
                                                    + ": "
                                                    + errorBody);
                                }

                                // Parse and return response
                                ResponseBody body = response.body();
                                if (body == null) {
                                    // Return empty response object if body is null
                                    return objectMapper.readValue("{}", responseType);
                                }
                                String responseBody = body.string();
                                if (responseBody == null || responseBody.trim().isEmpty()) {
                                    // Return empty response object if body is empty
                                    return objectMapper.readValue("{}", responseType);
                                }
                                return objectMapper.readValue(responseBody, responseType);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Adds memories to ReMe by sending trajectories for processing.
     *
     * <p>This method calls the {@code POST /summary_personal_memory} endpoint. ReMe will
     * process the trajectories and extract memorable information.
     *
     * <p>The operation is performed asynchronously on the bounded elastic scheduler
     * to avoid blocking the caller thread.
     *
     * @param request The add request containing trajectories and workspace ID
     * @return A Mono emitting the response
     */
    public Mono<ReMeAddResponse> add(ReMeAddRequest request) {
        return executePost(
                SUMMARY_ENDPOINT, request, ReMeAddResponse.class, "summary_personal_memory");
    }

    /**
     * Searches memories in ReMe using the provided query.
     *
     * <p>This method calls the {@code POST /retrieve_personal_memory} endpoint to find
     * memories relevant to the query string.
     *
     * <p>The operation is performed asynchronously on the bounded elastic scheduler
     * to avoid blocking the caller thread.
     *
     * @param request The search request containing query, workspace ID, and topK
     * @return A Mono emitting the search response with relevant memories
     */
    public Mono<ReMeSearchResponse> search(ReMeSearchRequest request) {
        return executePost(
                RETRIEVE_ENDPOINT, request, ReMeSearchResponse.class, "retrieve_personal_memory");
    }

    /**
     * Shuts down the HTTP client and releases resources.
     *
     * <p>This method should be called when the client is no longer needed.
     * After calling this method, the client should not be used for further requests.
     */
    public void shutdown() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}
