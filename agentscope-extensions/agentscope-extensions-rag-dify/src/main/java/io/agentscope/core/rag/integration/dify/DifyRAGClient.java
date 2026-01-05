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
package io.agentscope.core.rag.integration.dify;

import io.agentscope.core.rag.integration.dify.exception.DifyApiException;
import io.agentscope.core.rag.integration.dify.exception.DifyAuthException;
import io.agentscope.core.rag.integration.dify.model.DifyResponse;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Client for interacting with Dify Knowledge Base API.
 *
 * <p>This class wraps OkHttp client and provides reactive API methods for
 * knowledge base operations. It handles HTTP request construction, authentication,
 * error handling, and response parsing.
 *
 * <p>Example usage:
 * <pre>{@code
 * DifyRAGConfig config = DifyRAGConfig.builder()
 *     .apiKey(...)
 *     .datasetId(...)
 *     .build();
 *
 * DifyRAGClient client = new DifyRAGClient(config);
 * DifyResponse response = client.retrieve("query text", 10).block();
 * }</pre>
 */
public class DifyRAGClient {

    private static final Logger log = LoggerFactory.getLogger(DifyRAGClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final DifyRAGConfig config;
    private final JsonCodec jsonCodec;

    /**
     * Creates a new DifyRAGClient instance.
     *
     * @param config the Dify configuration
     */
    public DifyRAGClient(DifyRAGConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("DifyRAGConfig cannot be null");
        }

        this.config = config;
        this.jsonCodec = JsonUtils.getJsonCodec();
        this.httpClient = createHttpClient(config);

        log.info(
                "DifyRAGClient initialized for dataset: {} at endpoint: {}",
                config.getDatasetId(),
                config.getApiBaseUrl());
    }

    /**
     * Package-private constructor for testing purposes.
     *
     * <p>This constructor allows injecting a mock HTTP client for unit testing
     * without requiring actual Dify API access.
     *
     * @param httpClient the HTTP client
     * @param config the Dify configuration
     * @param jsonCodec the JSON codec
     */
    DifyRAGClient(OkHttpClient httpClient, DifyRAGConfig config, JsonCodec jsonCodec) {
        if (httpClient == null) {
            throw new IllegalArgumentException("HTTP client cannot be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("DifyRAGConfig cannot be null");
        }

        this.httpClient = httpClient;
        this.config = config;
        this.jsonCodec = jsonCodec != null ? jsonCodec : JsonUtils.getJsonCodec();

        log.info("DifyRAGClient initialized for testing with dataset: {}", config.getDatasetId());
    }

    /**
     * Retrieves relevant documents from the knowledge base.
     *
     * <p>This method searches the Dify knowledge base for documents relevant to
     * the given query. The retrieval mode (keyword/semantic/hybrid) is configured
     * in DifyRAGConfig.
     *
     * <p>API endpoint: POST /datasets/{dataset_id}/retrieve
     *
     * @param query the search query text
     * @param limit the maximum number of documents to retrieve (null for default)
     * @return a Mono emitting the Dify API response
     */
    public Mono<DifyResponse> retrieve(String query, Integer limit) {
        if (query == null || query.trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Query cannot be null or empty"));
        }

        return Mono.fromCallable(
                () -> {
                    log.debug(
                            "Retrieving from dataset: {} with query: {} (limit: {})",
                            config.getDatasetId(),
                            truncate(query, 50),
                            limit);

                    // Build retrieval_model object
                    Map<String, Object> retrievalModel = new HashMap<>();

                    // search_method: hybrid_search, semantic_search, keyword_search,
                    // full_text_search
                    retrievalModel.put("search_method", config.getRetrievalMode().getValue());

                    // top_k
                    int topK = limit != null ? limit : config.getTopK();
                    retrievalModel.put("top_k", topK);

                    // score_threshold
                    if (config.getScoreThreshold() != null && config.getScoreThreshold() > 0) {
                        retrievalModel.put("score_threshold_enabled", true);
                        retrievalModel.put("score_threshold", config.getScoreThreshold());
                    } else {
                        retrievalModel.put("score_threshold_enabled", false);
                    }

                    // reranking configuration
                    if (Boolean.TRUE.equals(config.getEnableRerank())) {
                        retrievalModel.put("reranking_enable", true);

                        if (config.getRerankConfig() != null) {
                            Map<String, Object> rerankingMode = new HashMap<>();
                            if (config.getRerankConfig().getProviderName() != null) {
                                rerankingMode.put(
                                        "reranking_provider_name",
                                        config.getRerankConfig().getProviderName());
                            }
                            if (config.getRerankConfig().getModelName() != null) {
                                rerankingMode.put(
                                        "reranking_model_name",
                                        config.getRerankConfig().getModelName());
                            }
                            if (!rerankingMode.isEmpty()) {
                                retrievalModel.put("reranking_mode", rerankingMode);
                            }
                        }
                    } else {
                        retrievalModel.put("reranking_enable", false);
                    }

                    // weights for hybrid search
                    if (config.getWeights() != null) {
                        retrievalModel.put("weights", config.getWeights());
                    }

                    // metadata filtering conditions
                    if (config.getMetadataFilter() != null) {
                        Map<String, Object> metadataFilteringConditions = new HashMap<>();
                        metadataFilteringConditions.put(
                                "logical_operator",
                                config.getMetadataFilter().getLogicalOperator());

                        List<Map<String, String>> conditions = new ArrayList<>();
                        for (MetadataFilterCondition condition :
                                config.getMetadataFilter().getConditions()) {
                            Map<String, String> conditionMap = new HashMap<>();
                            conditionMap.put("name", condition.getName());
                            conditionMap.put(
                                    "comparison_operator", condition.getComparisonOperator());
                            conditionMap.put("value", condition.getValue());
                            conditions.add(conditionMap);
                        }
                        metadataFilteringConditions.put("conditions", conditions);

                        retrievalModel.put(
                                "metadata_filtering_conditions", metadataFilteringConditions);
                    }

                    // Build request body
                    Map<String, Object> requestBody = new HashMap<>();
                    requestBody.put("query", query);
                    requestBody.put("retrieval_model", retrievalModel);

                    // Build URL: /datasets/{dataset_id}/retrieve
                    String url =
                            config.getApiBaseUrl()
                                    + "/datasets/"
                                    + config.getDatasetId()
                                    + "/retrieve";

                    // Build request
                    String jsonBody = jsonCodec.toJson(requestBody);
                    log.debug("Dify API request body: {}", jsonBody);

                    Request.Builder requestBuilder =
                            new Request.Builder()
                                    .url(url)
                                    .post(RequestBody.create(jsonBody, JSON))
                                    .addHeader("Authorization", "Bearer " + config.getApiKey())
                                    .addHeader("Content-Type", "application/json");

                    // Add custom headers
                    for (Map.Entry<String, String> header : config.getCustomHeaders().entrySet()) {
                        requestBuilder.addHeader(header.getKey(), header.getValue());
                    }

                    Request request = requestBuilder.build();

                    // Execute request
                    try (Response response = httpClient.newCall(request).execute()) {
                        String responseBody =
                                response.body() != null ? response.body().string() : "";

                        log.debug(
                                "Dify API response: status={}, bodyLength={}",
                                response.code(),
                                responseBody.length());

                        // Handle errors
                        if (!response.isSuccessful()) {
                            handleErrorResponse(response.code(), responseBody);
                        }

                        // Parse response
                        DifyResponse difyResponse =
                                jsonCodec.fromJson(responseBody, DifyResponse.class);

                        if (difyResponse == null) {
                            log.warn("Dify API returned null response");
                            return new DifyResponse(); // Return empty response
                        }

                        int resultCount =
                                difyResponse.getRecords() != null
                                        ? difyResponse.getRecords().size()
                                        : 0;
                        log.debug(
                                "Retrieved {} documents from dataset: {}",
                                resultCount,
                                config.getDatasetId());

                        return difyResponse;
                    }
                });
    }

    /**
     * Handles error responses from Dify API.
     *
     * @param statusCode the HTTP status code
     * @param responseBody the response body
     * @throws DifyApiException if an API error occurs
     */
    private void handleErrorResponse(int statusCode, String responseBody) {
        log.error("Dify API error: status={}, body={}", statusCode, truncate(responseBody, 200));

        // Parse error message if possible
        String errorMessage = "Dify API error";
        String errorCode = null;

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> errorBody = jsonCodec.fromJson(responseBody, Map.class);
            if (errorBody.containsKey("message")) {
                errorMessage = errorBody.get("message").toString();
            }
            if (errorBody.containsKey("code")) {
                errorCode = errorBody.get("code").toString();
            }
        } catch (Exception e) {
            // If parsing fails, use raw response body
            if (responseBody != null && !responseBody.isEmpty()) {
                errorMessage = responseBody;
            }
        }

        // Throw appropriate exception
        if (statusCode == 401 || statusCode == 403) {
            throw new DifyAuthException(statusCode, "Authentication failed: " + errorMessage);
        } else {
            throw new DifyApiException(statusCode, errorCode, errorMessage);
        }
    }

    /**
     * Creates and configures the OkHttp client.
     *
     * @param config the Dify configuration
     * @return a configured OkHttpClient instance
     */
    private OkHttpClient createHttpClient(DifyRAGConfig config) {
        OkHttpClient.Builder builder =
                new OkHttpClient.Builder()
                        .connectTimeout(
                                config.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .readTimeout(config.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .writeTimeout(config.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS);

        // Add retry interceptor if maxRetries > 0
        if (config.getMaxRetries() > 0) {
            builder.addInterceptor(new RetryInterceptor(config.getMaxRetries()));
        }

        // Add logging interceptor in debug mode
        if (log.isDebugEnabled()) {
            builder.addInterceptor(new LoggingInterceptor());
        }

        return builder.build();
    }

    /**
     * Gets the Dify configuration.
     *
     * @return the configuration
     */
    public DifyRAGConfig getConfig() {
        return config;
    }

    /**
     * Truncates a string to specified length with ellipsis.
     *
     * @param text the text to truncate
     * @param maxLength the maximum length
     * @return the truncated text
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }

    /**
     * Retry interceptor for OkHttp.
     */
    private static class RetryInterceptor implements Interceptor {
        private final int maxRetries;

        RetryInterceptor(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            Response response = null;
            IOException lastException = null;

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    if (response != null) {
                        response.close();
                    }

                    response = chain.proceed(request);

                    // Retry on 5xx errors or 429 (rate limit)
                    if (response.isSuccessful()
                            || (response.code() >= 400
                                    && response.code() < 500
                                    && response.code() != 429)) {
                        return response;
                    }

                    if (attempt < maxRetries) {
                        log.debug(
                                "Request failed with status {}, retrying... (attempt {}/{})",
                                response.code(),
                                attempt + 1,
                                maxRetries);

                        // Exponential backoff
                        try {
                            Thread.sleep((long) Math.pow(2, attempt) * 1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Retry interrupted", e);
                        }
                    }
                } catch (IOException e) {
                    lastException = e;
                    if (attempt < maxRetries) {
                        log.debug(
                                "Request failed with exception, retrying... (attempt {}/{})",
                                attempt + 1,
                                maxRetries,
                                e);

                        try {
                            Thread.sleep((long) Math.pow(2, attempt) * 1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Retry interrupted", ie);
                        }
                    }
                }
            }

            if (response != null) {
                return response;
            }
            throw lastException != null ? lastException : new IOException("Max retries exceeded");
        }
    }

    /**
     * Logging interceptor for OkHttp (debug mode only).
     */
    private static class LoggingInterceptor implements Interceptor {
        private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();

            log.debug("HTTP Request: {} {}", request.method(), request.url());

            long startTime = System.currentTimeMillis();
            Response response = chain.proceed(request);
            long duration = System.currentTimeMillis() - startTime;

            log.debug("HTTP Response: {} {} ({}ms)", response.code(), response.message(), duration);

            return response;
        }
    }
}
