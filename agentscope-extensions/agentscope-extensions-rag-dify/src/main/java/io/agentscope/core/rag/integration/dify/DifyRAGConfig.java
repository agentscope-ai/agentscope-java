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

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for Dify Knowledge Base integration.
 *
 * <p>This class contains all necessary configuration parameters to connect to
 * and interact with Dify Knowledge Base service, including connection settings,
 * retrieval parameters, and HTTP client settings.
 *
 * <p>Example usage:
 * <pre>{@code
 * DifyRAGConfig config = DifyRAGConfig.builder()
 *     .apiKey(System.getenv("DIFY_RAG_API_KEY"))
 *     .datasetId("your-dataset-id")  // Configure in code
 *     .apiBaseUrl("https://api.dify.ai/v1")  // Optional, for self-hosted
 *     .retrievalMode(RetrievalMode.HYBRID_SEARCH)
 *     .topK(10)
 *     .scoreThreshold(0.5)
 *     .enableRerank(true)
 *     .rerankConfig(RerankConfig.builder()
 *         .topN(5)
 *         .build())
 *     .build();
 * }</pre>
 */
public class DifyRAGConfig {

    private static final String DEFAULT_API_BASE_URL = "https://api.dify.ai/v1";
    private static final RetrievalMode DEFAULT_RETRIEVAL_MODE = RetrievalMode.HYBRID_SEARCH;
    private static final Integer DEFAULT_TOP_K = 10;
    private static final Double DEFAULT_SCORE_THRESHOLD = 0.0;
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(60);
    private static final Integer DEFAULT_MAX_RETRIES = 3;

    // Connection configuration
    private final String apiKey;
    private final String apiBaseUrl;
    private final String datasetId;

    // Retrieval configuration
    private final RetrievalMode retrievalMode;
    private final Integer topK;
    private final Double scoreThreshold;

    // Reranking configuration
    private final Boolean enableRerank;
    private final RerankConfig rerankConfig;

    // Hybrid search weights (0-1, for hybrid_search mode)
    private final Double weights;

    // Metadata filtering
    private final MetadataFilter metadataFilter;

    // HTTP configuration
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Integer maxRetries;

    // Advanced configuration
    private final Map<String, String> customHeaders;

    private DifyRAGConfig(Builder builder) {
        // Validate required fields
        if (builder.apiKey == null || builder.apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("API key cannot be null or empty");
        }
        if (builder.datasetId == null || builder.datasetId.trim().isEmpty()) {
            throw new IllegalArgumentException("Dataset ID cannot be null or empty");
        }

        // Connection configuration
        this.apiKey = builder.apiKey.trim();
        this.apiBaseUrl =
                (builder.apiBaseUrl != null && !builder.apiBaseUrl.trim().isEmpty())
                        ? builder.apiBaseUrl.trim()
                        : DEFAULT_API_BASE_URL;
        this.datasetId = builder.datasetId.trim();

        // Retrieval configuration
        this.retrievalMode =
                builder.retrievalMode != null ? builder.retrievalMode : DEFAULT_RETRIEVAL_MODE;
        this.topK = builder.topK != null ? builder.topK : DEFAULT_TOP_K;
        this.scoreThreshold =
                builder.scoreThreshold != null ? builder.scoreThreshold : DEFAULT_SCORE_THRESHOLD;

        // Reranking configuration
        this.enableRerank = builder.enableRerank;
        this.rerankConfig = builder.rerankConfig;

        // Hybrid search weights
        this.weights = builder.weights;

        // Metadata filtering
        this.metadataFilter = builder.metadataFilter;

        // HTTP configuration
        this.connectTimeout =
                builder.connectTimeout != null ? builder.connectTimeout : DEFAULT_CONNECT_TIMEOUT;
        this.readTimeout = builder.readTimeout != null ? builder.readTimeout : DEFAULT_READ_TIMEOUT;
        this.maxRetries = builder.maxRetries != null ? builder.maxRetries : DEFAULT_MAX_RETRIES;

        // Advanced configuration
        this.customHeaders =
                builder.customHeaders != null
                        ? new HashMap<>(builder.customHeaders)
                        : new HashMap<>();
    }

    /**
     * Gets the Dify API key.
     *
     * @return the API key
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * Gets the Dify API base URL.
     *
     * @return the base URL
     */
    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    /**
     * Gets the dataset (knowledge base) ID.
     *
     * @return the dataset ID
     */
    public String getDatasetId() {
        return datasetId;
    }

    /**
     * Gets the retrieval mode.
     *
     * @return the retrieval mode
     */
    public RetrievalMode getRetrievalMode() {
        return retrievalMode;
    }

    /**
     * Gets the top K value for retrieval.
     *
     * @return the top K value (1-100)
     */
    public Integer getTopK() {
        return topK;
    }

    /**
     * Gets the score threshold for filtering results.
     *
     * @return the score threshold (0.0-1.0)
     */
    public Double getScoreThreshold() {
        return scoreThreshold;
    }

    /**
     * Checks if reranking is enabled.
     *
     * @return true if reranking is enabled, null if not configured
     */
    public Boolean getEnableRerank() {
        return enableRerank;
    }

    /**
     * Gets the rerank configuration.
     *
     * @return the rerank config, or null if not set
     */
    public RerankConfig getRerankConfig() {
        return rerankConfig;
    }

    /**
     * Gets the hybrid search weights.
     *
     * <p>This parameter is used when retrieval mode is HYBRID_SEARCH.
     * It controls the balance between keyword and semantic search.
     *
     * @return the weights value (0-1), or null if not set
     */
    public Double getWeights() {
        return weights;
    }

    /**
     * Gets the metadata filter configuration.
     *
     * @return the metadata filter, or null if not set
     */
    public MetadataFilter getMetadataFilter() {
        return metadataFilter;
    }

    /**
     * Gets the HTTP connection timeout.
     *
     * @return the connection timeout
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * Gets the HTTP read timeout.
     *
     * @return the read timeout
     */
    public Duration getReadTimeout() {
        return readTimeout;
    }

    /**
     * Gets the maximum number of retries for failed requests.
     *
     * @return the max retries
     */
    public Integer getMaxRetries() {
        return maxRetries;
    }

    /**
     * Gets custom HTTP headers.
     *
     * @return a copy of custom headers map
     */
    public Map<String, String> getCustomHeaders() {
        return new HashMap<>(customHeaders);
    }

    /**
     * Creates a new builder for DifyRAGConfig.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for DifyRAGConfig.
     */
    public static class Builder {
        // Connection configuration
        private String apiKey;
        private String apiBaseUrl;
        private String datasetId;

        // Retrieval configuration
        private RetrievalMode retrievalMode;
        private Integer topK;
        private Double scoreThreshold;

        // Reranking configuration
        private Boolean enableRerank;
        private RerankConfig rerankConfig;

        // Hybrid search weights
        private Double weights;

        // Metadata filtering
        private MetadataFilter metadataFilter;

        // HTTP configuration
        private Duration connectTimeout;
        private Duration readTimeout;
        private Integer maxRetries;

        // Advanced configuration
        private Map<String, String> customHeaders;

        private Builder() {}

        /**
         * Sets the Dify API key.
         *
         * <p>Get your dataset API key from Dify console
         *
         * @param apiKey the API key (format: dataset-xxxxxxxxxxxxxxxx)
         * @return this builder for method chaining
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the Dify API base URL.
         *
         * <p>For Dify Cloud, use the default: https://api.dify.ai/v1
         * <p>For self-hosted Dify, set your instance URL, e.g.: https://your-dify.com/v1
         *
         * <p>If not set, defaults to https://api.dify.ai/v1
         *
         * @param apiBaseUrl the API base URL (must include /v1 suffix)
         * @return this builder for method chaining
         */
        public Builder apiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
            return this;
        }

        /**
         * Sets the dataset (knowledge base) ID.
         *
         * <p>Get your dataset ID from Dify console: can got by url
         * Format: UUID
         *
         * @param datasetId the dataset ID
         * @return this builder for method chaining
         */
        public Builder datasetId(String datasetId) {
            this.datasetId = datasetId;
            return this;
        }

        /**
         * Sets the retrieval mode.
         *
         * <p>Available modes:
         * <ul>
         *   <li>KEYWORD: Traditional keyword search
         *   <li>SEMANTIC: Vector similarity search
         *   <li>HYBRID: Combined keyword + semantic (recommended)
         *   <li>FULLTEXT: Full-text search
         * </ul>
         *
         * <p>Default: HYBRID
         *
         * @param retrievalMode the retrieval mode
         * @return this builder for method chaining
         */
        public Builder retrievalMode(RetrievalMode retrievalMode) {
            this.retrievalMode = retrievalMode;
            return this;
        }

        /**
         * Sets the top K value for retrieval.
         *
         * <p>Number of documents to retrieve from the knowledge base.
         * Range: [1-100], default: 10
         *
         * @param topK the top K value
         * @return this builder for method chaining
         * @throws IllegalArgumentException if topK is not in valid range
         */
        public Builder topK(Integer topK) {
            if (topK != null && (topK < 1)) {
                throw new IllegalArgumentException("topK must be larger than 1");
            }
            this.topK = topK;
            return this;
        }

        /**
         * Sets the score threshold for filtering results.
         *
         * <p>Only documents with similarity score >= threshold will be returned.
         * Range: [0.0-1.0], default: 0.0 (no filtering)
         *
         * @param scoreThreshold the score threshold
         * @return this builder for method chaining
         * @throws IllegalArgumentException if scoreThreshold is not in valid range
         */
        public Builder scoreThreshold(Double scoreThreshold) {
            if (scoreThreshold != null && (scoreThreshold < 0.0 || scoreThreshold > 1.0)) {
                throw new IllegalArgumentException("scoreThreshold must be between 0.0 and 1.0");
            }
            this.scoreThreshold = scoreThreshold;
            return this;
        }

        /**
         * Sets whether to enable reranking.
         *
         * <p>Reranking uses a specialized model to re-score and reorder retrieved
         * documents, improving relevance at the cost of slightly slower response.
         *
         * <p>Default: false
         *
         * @param enableRerank true to enable reranking
         * @return this builder for method chaining
         */
        public Builder enableRerank(Boolean enableRerank) {
            this.enableRerank = enableRerank;
            return this;
        }

        /**
         * Sets the rerank configuration.
         *
         * <p>Only takes effect when enableRerank is true.
         *
         * @param rerankConfig the rerank configuration
         * @return this builder for method chaining
         */
        public Builder rerankConfig(RerankConfig rerankConfig) {
            this.rerankConfig = rerankConfig;
            return this;
        }

        /**
         * Sets the hybrid search weights.
         *
         * <p>This parameter is used when retrieval mode is HYBRID_SEARCH.
         * It controls the balance between keyword and semantic search.
         * A value closer to 1 emphasizes semantic search, closer to 0 emphasizes keyword search.
         *
         * <p>Range: [0.0-1.0]
         *
         * @param weights the weights value
         * @return this builder for method chaining
         * @throws IllegalArgumentException if weights is not in valid range
         */
        public Builder weights(Double weights) {
            if (weights != null && (weights < 0.0 || weights > 1.0)) {
                throw new IllegalArgumentException("weights must be between 0.0 and 1.0");
            }
            this.weights = weights;
            return this;
        }

        /**
         * Sets the metadata filter configuration.
         *
         * <p>Use this to filter documents based on their metadata fields.
         *
         * @param metadataFilter the metadata filter configuration
         * @return this builder for method chaining
         */
        public Builder metadataFilter(MetadataFilter metadataFilter) {
            this.metadataFilter = metadataFilter;
            return this;
        }

        /**
         * Sets the HTTP connection timeout.
         *
         * <p>Default: 30 seconds
         *
         * @param connectTimeout the connection timeout
         * @return this builder for method chaining
         */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /**
         * Sets the HTTP read timeout.
         *
         * <p>Default: 60 seconds
         *
         * @param readTimeout the read timeout
         * @return this builder for method chaining
         */
        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        /**
         * Sets the maximum number of retries for failed requests.
         *
         * <p>Default: 3
         *
         * @param maxRetries the max retries (0 = no retry)
         * @return this builder for method chaining
         * @throws IllegalArgumentException if maxRetries is negative
         */
        public Builder maxRetries(Integer maxRetries) {
            if (maxRetries != null && maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries cannot be negative");
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Sets custom HTTP headers to include in all requests.
         *
         * <p>Useful for adding custom authentication, tracing headers, etc.
         *
         * @param customHeaders map of header name to value
         * @return this builder for method chaining
         */
        public Builder customHeaders(Map<String, String> customHeaders) {
            this.customHeaders = customHeaders != null ? new HashMap<>(customHeaders) : null;
            return this;
        }

        /**
         * Adds a single custom HTTP header.
         *
         * @param name the header name
         * @param value the header value
         * @return this builder for method chaining
         */
        public Builder addCustomHeader(String name, String value) {
            if (this.customHeaders == null) {
                this.customHeaders = new HashMap<>();
            }
            this.customHeaders.put(name, value);
            return this;
        }

        /**
         * Builds a new DifyRAGConfig instance.
         *
         * @return a new DifyRAGConfig instance
         * @throws IllegalArgumentException if required parameters are missing or invalid
         */
        public DifyRAGConfig build() {
            return new DifyRAGConfig(this);
        }
    }

    @Override
    public String toString() {
        return "DifyRAGConfig{"
                + "apiBaseUrl='"
                + apiBaseUrl
                + '\''
                + ", datasetId='"
                + datasetId
                + '\''
                + ", retrievalMode="
                + retrievalMode
                + ", topK="
                + topK
                + ", scoreThreshold="
                + scoreThreshold
                + ", enableRerank="
                + enableRerank
                + ", rerankConfig="
                + rerankConfig
                + ", weights="
                + weights
                + ", metadataFilter="
                + metadataFilter
                + ", connectTimeout="
                + connectTimeout
                + ", readTimeout="
                + readTimeout
                + ", maxRetries="
                + maxRetries
                + '}';
    }
}
