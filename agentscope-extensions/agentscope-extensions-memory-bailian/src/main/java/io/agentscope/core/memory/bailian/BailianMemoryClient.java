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
package io.agentscope.core.memory.bailian;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * HTTP client for interacting with Bailian Long Term Memory API.
 *
 * <p>This client provides reactive API methods for memory operations including
 * adding memories and searching for relevant memories using semantic similarity.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Using builder
 * BailianMemoryClient client = BailianMemoryClient.builder()
 *     .apiKey(System.getenv("DASHSCOPE_API_KEY"))
 *     .build();
 *
 * // Add memory
 * BailianAddRequest addRequest = BailianAddRequest.builder()
 *     .userId("user_001")
 *     .memoryLibraryId("memory_library_123")
 *     .projectId(project_id_123);
 *     .profileSchema(profile_schema_123);
 *     .messages(messages)
 *     .build();
 * client.add(addRequest).block();
 *
 * // Search memory
 * BailianSearchRequest searchRequest = BailianSearchRequest.builder()
 *     .userId("user_001")
 *     .messages(searchMessages)
 *     .memoryLibraryId("memory_library_123")
 *     .projectIds(List.of("project_id_123"));
 *     .topK(5)
 *     .build();
 * BailianSearchResponse response = client.search(searchRequest).block();
 *
 * // Close the client when done
 * client.close();
 * }</pre>
 */
public class BailianMemoryClient {

    private static final Logger log = LoggerFactory.getLogger(BailianMemoryClient.class);
    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com";
    private static final String ADD_MEMORY_ENDPOINT = "/api/v2/apps/memory/add";
    private static final String SEARCH_MEMORY_ENDPOINT = "/api/v2/apps/memory/memory_nodes/search";

    private final String apiBaseUrl;
    private final String apiKey;
    private final HttpTransport httpTransport;
    private final JsonCodec jsonCodec;

    /**
     * Creates a new {@link BailianMemoryClient} instance.
     *
     * @param builder the builder for configuring the client
     */
    private BailianMemoryClient(Builder builder) {
        if (builder.apiBaseUrl == null || builder.apiBaseUrl.isBlank()) {
            throw new IllegalArgumentException("apiBaseUrl cannot be null or blank");
        }
        if (builder.apiKey == null || builder.apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey cannot be null or blank");
        }
        if (builder.httpTransport == null) {
            throw new IllegalArgumentException("httpTransport cannot be null");
        }
        this.apiBaseUrl = builder.apiBaseUrl;
        this.apiKey = builder.apiKey;
        this.httpTransport = builder.httpTransport;
        this.jsonCodec = JsonUtils.getJsonCodec();
    }

    /**
     * Adds memories to Bailian Long Term Memory.
     *
     * <p>This method calls the POST /api/v2/apps/memory/add endpoint to store
     * conversation messages as memory nodes. Bailian will automatically extract
     * key information from the messages.
     *
     * @param request the add memory request containing messages and metadata
     * @return a Mono emitting the add memory response
     */
    public Mono<BailianAddResponse> add(BailianAddRequest request) {
        return executePost(ADD_MEMORY_ENDPOINT, request, BailianAddResponse.class, "add memory");
    }

    /**
     * Searches for relevant memories based on semantic similarity.
     *
     * <p>This method calls the POST /api/v2/apps/memory/memory_nodes/search endpoint
     * to find memories relevant to the provided query messages. Results are ordered
     * by relevance score.
     *
     * @param request the search memory request containing query and filters
     * @return a Mono emitting the search memory response
     */
    public Mono<BailianSearchResponse> search(BailianSearchRequest request) {
        return executePost(
                SEARCH_MEMORY_ENDPOINT, request, BailianSearchResponse.class, "search memory");
    }

    /**
     * Executes a POST request to the Bailian API.
     *
     * <p>This method serializes the request object to JSON, sends it to the
     * specified endpoint, and parses the response. All HTTP operations are
     * performed on the bounded elastic scheduler.
     *
     * @param endPoint the API endPoint (e.g., "/api/v2/apps/memory/add")
     * @param request the request object to serialize
     * @param responseType the response class type for parsing
     * @param operationName the operation name for logging
     * @param <T> the request type
     * @param <R> the response type
     * @return a Mono emitting the parsed response
     */
    private <T, R> Mono<R> executePost(
            String endPoint, T request, Class<R> responseType, String operationName) {
        return Mono.fromCallable(
                        () -> {
                            String json = jsonCodec.toJson(request);

                            HttpRequest httpRequest =
                                    HttpRequest.builder()
                                            .url(apiBaseUrl + endPoint)
                                            .method("POST")
                                            .header("Authorization", "Bearer " + apiKey)
                                            .header("Content-Type", "application/json")
                                            .body(json)
                                            .build();

                            log.debug(
                                    "Executing {} request to {}",
                                    operationName,
                                    httpRequest.getUrl());

                            HttpResponse response = httpTransport.execute(httpRequest);

                            if (!response.isSuccessful()) {
                                throw new IOException(
                                        "Bailian API "
                                                + operationName
                                                + " failed with status "
                                                + response.getStatusCode()
                                                + ": "
                                                + response.getBody());
                            }

                            String responseBody = response.getBody();
                            if (responseBody == null || responseBody.isEmpty()) {
                                throw new IOException(
                                        "Bailian API "
                                                + operationName
                                                + " returned empty response");
                            }

                            return jsonCodec.fromJson(responseBody, responseType);
                        })
                .onErrorMap(
                        th -> {
                            if (th instanceof IOException) {
                                return th;
                            }
                            return new IOException(
                                    "Bailian API " + operationName + " execute error", th);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Closes the HTTP transport and releases resources.
     */
    public void close() {
        if (httpTransport != null) {
            httpTransport.close();
        }
    }

    /**
     * Creates a new builder for {@link BailianMemoryClient}.
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link BailianMemoryClient}.
     */
    public static class Builder {
        private String apiBaseUrl = DEFAULT_BASE_URL;
        private String apiKey;
        private HttpTransport httpTransport = HttpTransportFactory.getDefault();

        /**
         * Sets the base URL for the Bailian API.
         *
         * @param apiBaseUrl the base URL for the Bailian API
         * @return this builder
         */
        public Builder apiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
            return this;
        }

        /**
         * Sets the API key for authentication.
         *
         * @param apiKey the Bailian API key
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the HTTP transport for API requests.
         *
         * <p>If not specified, the default HTTP transport from
         * {@link HttpTransportFactory#getDefault()} will be used.
         *
         * @param httpTransport the HTTP transport
         * @return this builder
         */
        public Builder httpTransport(HttpTransport httpTransport) {
            this.httpTransport = httpTransport;
            return this;
        }

        /**
         * Builds the {@link BailianMemoryClient} instance.
         *
         * @return a new {@link BailianMemoryClient} instance
         */
        public BailianMemoryClient build() {
            return new BailianMemoryClient(this);
        }
    }
}
