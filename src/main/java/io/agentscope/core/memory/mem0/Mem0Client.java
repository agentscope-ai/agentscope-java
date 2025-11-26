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
package io.agentscope.core.memory.mem0;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * HTTP client for interacting with the Mem0 API.
 */
public class Mem0Client {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String MEMORIES_ENDPOINT = "/v1/memories/";
    private static final String SEARCH_ENDPOINT = "/v2/memories/search/";

    private final OkHttpClient httpClient;
    private final String apiBaseUrl;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new Mem0Client with specified configuration.
     *
     * @param apiBaseUrl The base URL of the Mem0 API (e.g., "http://localhost:8000")
     * @param apiKey The API key for authentication (can be null for local deployments without
     *     authentication)
     */
    public Mem0Client(String apiBaseUrl, String apiKey) {
        this(apiBaseUrl, apiKey, Duration.ofSeconds(60));
    }

    /**
     * Creates a new Mem0Client with custom timeout.
     *
     * @param apiBaseUrl The base URL of the Mem0 API
     * @param apiKey The API key for authentication (can be null for local deployments without
     *     authentication)
     * @param timeout HTTP request timeout duration
     */
    public Mem0Client(String apiBaseUrl, String apiKey, Duration timeout) {
        this.apiBaseUrl =
                apiBaseUrl.endsWith("/")
                        ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1)
                        : apiBaseUrl;
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
        // Enables serialization/deserialization of Java date/time types
        this.objectMapper.registerModule(new JavaTimeModule());
        this.httpClient =
                new OkHttpClient.Builder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .readTimeout(timeout)
                        .writeTimeout(Duration.ofSeconds(30))
                        .build();
    }

    /**
     * Executes a POST request and returns the raw response body as a string.
     *
     * <p>This is a low-level method that handles HTTP communication, JSON serialization,
     * and error handling. The response parsing is left to the caller.
     *
     * @param endpoint The API endpoint path (e.g., "/v1/memories")
     * @param request The request object to serialize as JSON
     * @param operationName A human-readable name for the operation (for error messages)
     * @param <T> The request type
     * @return A Mono emitting the raw response body as a string
     * @throws IOException If the HTTP request fails
     */
    private <T> Mono<String> executePostRaw(String endpoint, T request, String operationName) {
        return Mono.fromCallable(
                        () -> {
                            // Serialize request to JSON
                            String json = objectMapper.writeValueAsString(request);

                            // Build HTTP request
                            Request.Builder requestBuilder =
                                    new Request.Builder()
                                            .url(apiBaseUrl + endpoint)
                                            .addHeader("Content-Type", "application/json")
                                            .post(RequestBody.create(json, JSON));

                            // Add Authorization header only if apiKey is provided
                            if (apiKey != null && !apiKey.isEmpty()) {
                                requestBuilder.addHeader("Authorization", "Token " + apiKey);
                            }

                            Request httpRequest = requestBuilder.build();

                            // Execute request
                            try (Response response = httpClient.newCall(httpRequest).execute()) {
                                if (!response.isSuccessful()) {
                                    String errorBody =
                                            response.body() != null
                                                    ? response.body().string()
                                                    : "No error details";
                                    throw new IOException(
                                            "Mem0 API "
                                                    + operationName
                                                    + " failed with status "
                                                    + response.code()
                                                    + ": "
                                                    + errorBody);
                                }

                                // Return raw response body
                                return response.body().string();
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Executes a POST request to the Mem0 API and parses the response.
     *
     * <p>This is a generic method that handles HTTP communication, JSON serialization,
     * error handling, and response parsing for all POST endpoints.
     *
     * @param endpoint The API endpoint path (e.g., "/v1/memories")
     * @param request The request object to serialize as JSON
     * @param responseType The class of the response type
     * @param operationName A human-readable name for the operation (for error messages)
     * @param <T> The request type
     * @param <R> The response type
     * @return A Mono emitting the parsed response
     * @throws IOException If the HTTP request fails or response cannot be parsed
     */
    private <T, R> Mono<R> executePost(
            String endpoint, T request, Class<R> responseType, String operationName) {
        return executePostRaw(endpoint, request, operationName)
                .map(
                        responseBody -> {
                            try {
                                return objectMapper.readValue(responseBody, responseType);
                            } catch (IOException e) {
                                throw new RuntimeException(
                                        "Failed to parse response for " + operationName, e);
                            }
                        });
    }

    /**
     * Adds memories to Mem0 by sending messages for processing.
     *
     * <p>This method calls the {@code POST /v1/memories} endpoint. Mem0 will process
     * the messages and extract memorable information using LLM inference (unless
     * {@code infer} is set to false in the request).
     *
     * <p>The operation is performed asynchronously on the bounded elastic scheduler
     * to avoid blocking the caller thread.
     *
     * @param request The add request containing messages and metadata
     * @return A Mono emitting the response with extracted memories
     */
    public Mono<Mem0AddResponse> add(Mem0AddRequest request) {
        return executePost(MEMORIES_ENDPOINT, request, Mem0AddResponse.class, "add request");
    }

    /**
     * Searches memories in Mem0 using semantic similarity.
     *
     * <p>This method calls the {@code POST /v2/memories/search/} endpoint to find
     * memories relevant to the query string. Results are ordered by relevance score
     * (highest first).
     *
     * <p>The v2 API returns a direct array of results, which this method wraps
     * into a Mem0SearchResponse object for consistency with the existing API.
     *
     * <p>The metadata filters (agent_id, user_id, run_id) in the request ensure
     * that only memories from the specified context are returned.
     *
     * <p>The operation is performed asynchronously on the bounded elastic scheduler
     * to avoid blocking the caller thread.
     *
     * @param request The search request containing query and filters
     * @return A Mono emitting the search response with relevant memories
     */
    public Mono<Mem0SearchResponse> search(Mem0SearchRequest request) {
        return executePostRaw(SEARCH_ENDPOINT, request, "search request")
                .map(
                        responseBody -> {
                            try {
                                // Parse response as array
                                List<Mem0SearchResult> results =
                                        objectMapper.readValue(
                                                responseBody,
                                                objectMapper
                                                        .getTypeFactory()
                                                        .constructCollectionType(
                                                                List.class,
                                                                Mem0SearchResult.class));

                                // Wrap in Mem0SearchResponse for consistency
                                Mem0SearchResponse searchResponse = new Mem0SearchResponse();
                                searchResponse.setResults(results);
                                return searchResponse;
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to parse search response", e);
                            }
                        });
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
