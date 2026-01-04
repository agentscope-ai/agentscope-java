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
package io.agentscope.core.rag.integration.ragflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.rag.integration.ragflow.exception.RAGFlowApiException;
import io.agentscope.core.rag.integration.ragflow.exception.RAGFlowAuthException;
import io.agentscope.core.rag.integration.ragflow.model.RAGFlowResponse;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * HTTP client for RAGFlow API.
 *
 * <p>This client handles all HTTP communication with RAGFlow service, including:
 * <ul>
 *   <li>Knowledge retrieval
 *   <li>Authentication
 *   <li>Error handling and retry logic
 * </ul>
 *
 * @author RAGFlow Integration Team
 */
public class RAGFlowClient {

    private static final Logger logger = LoggerFactory.getLogger(RAGFlowClient.class);

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;

    private final RAGFlowConfig config;

    private final ObjectMapper objectMapper;

    public RAGFlowClient(RAGFlowConfig config) {
        this(config, new ObjectMapper());
    }

    public RAGFlowClient(RAGFlowConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = createHttpClient();
    }

    RAGFlowClient(OkHttpClient httpClient, RAGFlowConfig config, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /**
     * Retrieve documents from RAGFlow knowledge base.
     *
     * <p>This method calls the RAGFlow retrieval API to search for relevant document chunks. The
     * API endpoint is: {@code POST /api/v1/retrieval}
     *
     * <p><b>Real API Format:</b>
     *
     * <pre>{@code
     * {
     *   "question": "What is advantage of ragflow?",
     *   "dataset_ids": ["b2a62730759d11ef987d0242ac120004"],
     *   "document_ids": ["77df9ef4759a11ef8bdd0242ac120004"],  // optional
     *   "top_k": 10,
     *   "similarity_threshold": 0.3,
     *   "metadata_condition": {
     *     "logic": "and",
     *     "conditions": [
     *       {"name": "author", "comparison_operator": "=", "value": "Toby"}
     *     ]
     *   }
     * }
     * }</pre>
     *
     * @param question the query text (required)
     * @param topK the number of documents to retrieve (optional, defaults to config value)
     * @param similarityThreshold the minimum similarity threshold (optional, defaults to config
     *     value)
     * @param metadataCondition metadata filtering conditions (optional)
     * @return a Mono emitting the retrieval response
     */
    public Mono<RAGFlowResponse> retrieve(
            String question,
            Integer topK,
            Double similarityThreshold,
            Map<String, Object> metadataCondition) {

        return Mono.fromCallable(
                () -> {
                    if (question == null || question.trim().isEmpty()) {
                        throw new IllegalArgumentException("Question cannot be null or empty");
                    }

                    Map<String, Object> requestBody = new HashMap<>();

                    // Required: question text
                    requestBody.put("question", question);

                    // Required: dataset_ids (array)
                    requestBody.put("dataset_ids", config.getDatasetIds());

                    // Optional: document_ids (filter to specific documents)
                    if (config.getDocumentIds() != null && !config.getDocumentIds().isEmpty()) {
                        requestBody.put("document_ids", config.getDocumentIds());
                    }

                    // Optional: top_k (default: 1024)
                    if (topK != null) {
                        requestBody.put("top_k", topK);
                    } else if (config.getTopK() != null) {
                        requestBody.put("top_k", config.getTopK());
                    }

                    // Optional: similarity_threshold (default: 0.2)
                    Double threshold =
                            similarityThreshold != null
                                    ? similarityThreshold
                                    : config.getSimilarityThreshold();
                    if (threshold != null) {
                        requestBody.put("similarity_threshold", threshold);
                    }

                    // Optional: vector_similarity_weight (default: 0.3)
                    if (config.getVectorSimilarityWeight() != null) {
                        requestBody.put(
                                "vector_similarity_weight", config.getVectorSimilarityWeight());
                    }

                    // Optional: page (default: 1)
                    if (config.getPage() != null) {
                        requestBody.put("page", config.getPage());
                    }

                    // Optional: page_size (default: 30)
                    if (config.getPageSize() != null) {
                        requestBody.put("page_size", config.getPageSize());
                    }

                    // Optional: use_kg - knowledge graph for multi-hop queries (default: false)
                    if (config.getUseKg() != null) {
                        requestBody.put("use_kg", config.getUseKg());
                    }

                    // Optional: toc_enhance - table of contents enhancement (default: false)
                    if (config.getTocEnhance() != null) {
                        requestBody.put("toc_enhance", config.getTocEnhance());
                    }

                    // Optional: rerank_id - rerank model ID
                    if (config.getRerankId() != null) {
                        requestBody.put("rerank_id", config.getRerankId());
                    }

                    // Optional: keyword - keyword-based matching (default: false)
                    if (config.getKeyword() != null) {
                        requestBody.put("keyword", config.getKeyword());
                    }

                    // Optional: highlight - highlighting matched terms (default: false)
                    if (config.getHighlight() != null) {
                        requestBody.put("highlight", config.getHighlight());
                    }

                    // Optional: cross_languages - cross-language retrieval
                    if (config.getCrossLanguages() != null
                            && !config.getCrossLanguages().isEmpty()) {
                        requestBody.put("cross_languages", config.getCrossLanguages());
                    }

                    // Optional: metadata_condition for filtering
                    if (metadataCondition != null && !metadataCondition.isEmpty()) {
                        requestBody.put("metadata_condition", metadataCondition);
                    } else if (config.getMetadataCondition() != null
                            && !config.getMetadataCondition().isEmpty()) {
                        requestBody.put("metadata_condition", config.getMetadataCondition());
                    }

                    String jsonBody = objectMapper.writeValueAsString(requestBody);

                    String url = config.getBaseUrl() + "/api/v1/retrieval";

                    logger.debug("RAGFlow retrieval request: URL={}, body={}", url, jsonBody);

                    Request request =
                            new Request.Builder()
                                    .url(url)
                                    .post(RequestBody.create(jsonBody, JSON))
                                    .addHeader("Authorization", "Bearer " + config.getApiKey())
                                    .addHeader("Content-Type", "application/json")
                                    .build();

                    // Add custom headers
                    if (config.getCustomHeaders() != null) {
                        Request.Builder builder = request.newBuilder();
                        config.getCustomHeaders()
                                .forEach((key, value) -> builder.addHeader(key, value));
                        request = builder.build();
                    }

                    try (Response response = httpClient.newCall(request).execute()) {
                        String responseBody =
                                response.body() != null ? response.body().string() : "";

                        logger.debug(
                                "RAGFlow API response: status={}, body={}",
                                response.code(),
                                responseBody);

                        if (!response.isSuccessful()) {
                            handleErrorResponse(response.code(), responseBody);
                        }

                        RAGFlowResponse ragFlowResponse =
                                objectMapper.readValue(responseBody, RAGFlowResponse.class);

                        // Check if response indicates an error
                        if (ragFlowResponse.getCode() != null && ragFlowResponse.getCode() != 0) {
                            throw new RAGFlowApiException(
                                    "RAGFlow API error: " + ragFlowResponse.getMessage(),
                                    ragFlowResponse.getCode());
                        }

                        // Log successful response details
                        if (logger.isDebugEnabled()
                                && ragFlowResponse.getData() != null
                                && ragFlowResponse.getData().getChunks() != null) {
                            int chunkCount = ragFlowResponse.getData().getChunks().size();
                            Integer total = ragFlowResponse.getData().getTotal();
                            logger.debug(
                                    "RAGFlow retrieval successful: retrieved {} chunks, total={}",
                                    chunkCount,
                                    total);

                            // Log document aggregations if available
                            if (ragFlowResponse.getData().getDocAggs() != null) {
                                logger.debug(
                                        "Document aggregations: {}",
                                        ragFlowResponse.getData().getDocAggs());
                            }
                        }

                        return ragFlowResponse;
                    }
                });
    }

    private void handleErrorResponse(int statusCode, String responseBody) {
        logger.error("RAGFlow API error: status={}, body={}", statusCode, responseBody);

        if (statusCode == 401 || statusCode == 403) {
            throw new RAGFlowAuthException("Authentication failed: " + responseBody, statusCode);
        } else if (statusCode == 404) {
            throw new RAGFlowApiException("Dataset not found: " + responseBody, statusCode);
        } else if (statusCode == 429) {
            throw new RAGFlowApiException("Rate limit exceeded: " + responseBody, statusCode);
        } else if (statusCode >= 500) {
            throw new RAGFlowApiException(
                    "RAGFlow server error (HTTP " + statusCode + "): " + responseBody, statusCode);
        } else {
            throw new RAGFlowApiException(
                    "RAGFlow API error (HTTP " + statusCode + "): " + responseBody, statusCode);
        }
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

            try {
                for (int attempt = 0; attempt <= maxRetries; attempt++) {
                    try {
                        if (response != null) {
                            response.close();
                        }
                        response = chain.proceed(request);

                        // Don't retry on successful responses or client errors (4xx)
                        if (response.isSuccessful() || response.code() < 500) {
                            return response;
                        }

                        logger.warn(
                                "RAGFlow request failed with status {}, attempt {}/{}",
                                response.code(),
                                attempt + 1,
                                maxRetries + 1);

                    } catch (IOException e) {
                        lastException = e;
                        logger.warn(
                                "RAGFlow request failed with exception, attempt {}/{}: {}",
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
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();

            logger.debug("RAGFlow HTTP Request: {} {}", request.method(), request.url());

            long startTime = System.currentTimeMillis();
            Response response = chain.proceed(request);
            long duration = System.currentTimeMillis() - startTime;

            logger.debug("RAGFlow HTTP Response: {} in {}ms", response.code(), duration);

            return response;
        }
    }
}
