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
package io.agentscope.core.rag.integration.haystack;

import io.agentscope.core.rag.integration.haystack.exception.HayStackApiException;
import io.agentscope.core.rag.integration.haystack.model.HayStackResponse;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Client for interacting with HayStack Knowledge Base API.
 *
 * <p>This class wraps OkHttp client and provides reactive API methods for
 * knowledge base operations. It handles HTTP request construction, error handling,
 * and response parsing.
 *
 * <p>Example usage:
 * <pre>{@code
 * HayStackConfig config = HayStackConfig.builder()
 *     .baseUrl(...)
 *     .topK(...)
 *     .build();
 *
 * HayStackClient client = new HayStackClient(config);
 * HayStackResponse response = client.retrieve("query text", 10).block();
 * }</pre>
 */
public class HayStackClient {

    private static final Logger logger = LoggerFactory.getLogger(HayStackClient.class);

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;

    private final HayStackConfig config;

    public HayStackClient(HayStackConfig config) {
        this.config = config;
        this.httpClient = createHttpClient();
    }

    HayStackClient(OkHttpClient httpClient, HayStackConfig config) {
        this.httpClient = httpClient;
        this.config = config;
    }

    /**
     * Retrieve documents from HayStack knowledge base.
     *
     * <p>This method calls the HayStack retrieval API to search for relevant document chunks.
     *
     * <p>Since Haystack does not provide a standard REST API format,
     * the API response of your <b>custom-built Haystack RAG server</b> should conform to the following format:
     *
     * <pre>{@code
     * {
     *   "code": 0,
     *   "documents": [
     *     {
     *       "id": "0fab3c1c6c433368",
     *       "content": "Test Content",
     *       "blob": null,
     *       "meta": {
     *         "file_path": "test.txt"
     *       },
     *       "score": 1.2303546667099,
     *       "embedding": [
     *         0.01008153147995472,
     *         -0.04555170238018036,
     *         -0.024434546008706093
     *       ],
     *       "sparse_embedding": null,
     *       "error": null
     *     }
     *   ]
     * }
     * }</pre>
     *
     *
     * <p> In typical scenarios, you can build your Haystack RAG server like this:
     *
     * <pre>{@code
     * @app.post("/retrieve", response_model=HayStackResponse)
     * async def retriever(req: Request):
     *     result = retriever.run(
     *         query=req.query,
     *         top_k=req.top_k,
     *     )
     *     documents = result["documents"]
     *
     *     return {
     *         "code": 0,
     *         "documents": documents,
     *         "error": None
     *     }
     * }</pre>
     *
     * @param query the query text (required)
     * @param topK  the number of documents to retrieve (optional, defaults to config value)
     * @return a Mono emitting the retrieval response
     */
    public Mono<HayStackResponse> retrieve(String query, Integer topK, Double scoreThreshold) {
        return Mono.fromCallable(
                () -> {
                    if (query == null || query.trim().isEmpty()) {
                        throw new IllegalArgumentException("Query cannot be null or empty");
                    }

                    Map<String, Object> requestBody = new HashMap<>();

                    // Required: question text
                    requestBody.put("query", query);

                    // Optional: top_k (default: 3)
                    if (topK != null) {
                        requestBody.put("top_k", topK);
                    } else if (config.getTopK() != null) {
                        requestBody.put("top_k", config.getTopK());
                    }

                    // Optional: scale_score
                    if (config.getScaleScore() != null) {
                        requestBody.put("scale_score", config.getScaleScore());
                    }

                    // Optional: return_embedding
                    if (config.getReturnEmbedding() != null) {
                        requestBody.put("return_embedding", config.getReturnEmbedding());
                    }

                    Double threshold =
                            scoreThreshold != null ? scoreThreshold : config.getScoreThreshold();

                    // Optional: score_threshold
                    if (threshold != null) {
                        requestBody.put("score_threshold", threshold);
                    }

                    // Optional: group
                    if (config.getGroupBy() != null) {
                        requestBody.put("group_by", config.getGroupBy());
                    }

                    // Optional: group_size
                    if (config.getGroupSize() != null) {
                        requestBody.put("group_size", config.getGroupSize());
                    }

                    // Optional: filters
                    if (config.getFilters() != null) {
                        requestBody.put("filters", config.getFilters());
                    }

                    // Optional: query_embedding
                    if (config.getQueryEmbedding() != null) {
                        requestBody.put("query_embedding", config.getQueryEmbedding());
                    }

                    // Optional: query_sparse_embedding
                    if (config.getQuerySparseEmbedding() != null) {
                        requestBody.put("query_sparse_embedding", config.getQuerySparseEmbedding());
                    }

                    // Optional: documents
                    if (config.getDocuments() != null) {
                        requestBody.put("documents", config.getDocuments());
                    }

                    // Optional: retrieved_documents
                    if (config.getRetrievedDocuments() != null) {
                        requestBody.put("retrieved_documents", config.getRetrievedDocuments());
                    }

                    // Optional: window_size
                    if (config.getWindowSize() != null) {
                        requestBody.put("window_size", config.getWindowSize());
                    }

                    String jsonBody = JsonUtils.getJsonCodec().toJson(requestBody);

                    String url = config.getBaseUrl();

                    logger.debug("HayStack retrieval request: URL={}, body={}", url, jsonBody);

                    Request request =
                            new Request.Builder()
                                    .url(url)
                                    .post(RequestBody.create(jsonBody, JSON))
                                    .addHeader("Content-Type", "application/json")
                                    .build();

                    // Add custom headers
                    if (config.getCustomHeaders() != null) {
                        Request.Builder builder = request.newBuilder();
                        config.getCustomHeaders().forEach(builder::addHeader);
                        request = builder.build();
                    }

                    try (Response response = httpClient.newCall(request).execute()) {
                        String responseBody =
                                response.body() != null ? response.body().string() : "";

                        logger.debug(
                                "HayStack API response: status={}, body={}",
                                response.code(),
                                responseBody);

                        if (!response.isSuccessful()) {
                            logger.error(
                                    "HayStack API error: status={}, body={}",
                                    response.code(),
                                    responseBody);
                        }

                        HayStackResponse hayStackResponse =
                                JsonUtils.getJsonCodec()
                                        .fromJson(responseBody, HayStackResponse.class);

                        // Check if response indicates an error
                        if (hayStackResponse.getCode() != null
                                && !hayStackResponse.getCode().equals(config.getSuccessCode())) {
                            throw new HayStackApiException(
                                    "HayStack API error: " + hayStackResponse.getError(),
                                    hayStackResponse.getCode());
                        }

                        return hayStackResponse;
                    }
                });
    }

    private OkHttpClient createHttpClient() {
        OkHttpClient.Builder builder =
                new OkHttpClient.Builder()
                        .connectTimeout(config.getTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .readTimeout(config.getTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .writeTimeout(config.getTimeout().toMillis(), TimeUnit.MILLISECONDS);

        // Add retry interceptor if max retries > 0
        if (config.getMaxRetries() != null && config.getMaxRetries() > 0) {
            builder.addInterceptor(new RetryInterceptor(config.getMaxRetries()));
        }

        // Add logging interceptor
        if (logger.isDebugEnabled()) {
            builder.addInterceptor(new LoggingInterceptor());
        }

        return builder.build();
    }

    /**
     * Interceptor for retrying failed requests.
     */
    private record RetryInterceptor(int maxRetries) implements Interceptor {

        @Override
        public @NonNull Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            Response response = null;
            IOException lastException = null;

            try {
                for (int attempt = 0; attempt <= maxRetries; attempt++) {
                    try {
                        if (response != null) {
                            response.close();
                        }
                        response = chain.proceed(request);

                        // Don't retry on successful responses or client errors (4xx)
                        if (response.isSuccessful() || response.code() < 500) {
                            lastException = null;
                            return response;
                        }

                        logger.warn(
                                "HayStack request failed with status {}, attempt {}/{}",
                                response.code(),
                                attempt + 1,
                                maxRetries + 1);

                    } catch (IOException e) {
                        lastException = e;
                        logger.warn(
                                "HayStack request failed with exception, attempt {}/{}: {}",
                                attempt + 1,
                                maxRetries + 1,
                                e.getMessage());

                        if (attempt == maxRetries) {
                            throw e;
                        }
                    }

                    // Wait before retry (exponential backoff)
                    if (attempt < maxRetries) {
                        try {
                            long waitTime = (long) Math.pow(2, attempt) * 1000;
                            Thread.sleep(Math.min(waitTime, 10000)); // Max 10 seconds
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Retry interrupted", e);
                        }
                    }
                }

                if (lastException != null) {
                    throw lastException;
                }

                return response;
            } finally {
                // Ensure response is closed if we're not returning it successfully
                if (response != null && (lastException != null || !response.isSuccessful())) {
                    response.close();
                }
            }
        }
    }

    /**
     * Interceptor for logging HTTP requests and responses.
     */
    private static class LoggingInterceptor implements Interceptor {

        @Override
        public @NonNull Response intercept(Chain chain) throws IOException {
            Request request = chain.request();

            logger.debug("HayStack HTTP Request: {} {}", request.method(), request.url());

            long startTime = System.currentTimeMillis();
            Response response = chain.proceed(request);
            long duration = System.currentTimeMillis() - startTime;

            logger.debug("HayStack HTTP Response: {} in {}ms", response.code(), duration);

            return response;
        }
    }
}
