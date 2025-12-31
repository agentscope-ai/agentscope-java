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

import io.agentscope.core.rag.integration.haystack.model.HayStackDocument;
import io.agentscope.core.rag.integration.haystack.model.SparseEmbedding;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for HayStack integration.
 *
 * <p>This class holds the configuration for connecting to HayStack service, including
 * endpoint, and retrieval parameters.
 *
 * <p><b>API Reference:</b> This configuration is designed to work with HayStack RAG's retrieval API.
 */
public class HayStackConfig {

    private static final int DEFAULT_TOP_K = 3;

    public static final int DEFAULT_SUCCESS_CODE = 0;

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static final int DEFAULT_MAX_RETRIES = 3;

    // Endpoint
    private final String baseUrl;

    private final Integer successCode;

    // Retrieval Parameters
    private final Integer topK;

    private final Boolean scaleScore;

    private final Boolean returnEmbedding;

    private final Double scoreThreshold;

    private final String groupBy;

    private final Integer groupSize;

    private final List<Float> queryEmbedding;

    private final SparseEmbedding querySparseEmbedding;

    private final List<HayStackDocument> documents;

    private final List<HayStackDocument> retrievedDocuments;

    private final Integer windowSize;

    private final Map<String, Object> filters;

    private final FilterPolicy filterPolicy;

    // HTTP Client Settings
    private final Duration timeout;

    private final Integer maxRetries;

    private final Map<String, String> customHeaders;

    private HayStackConfig(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.successCode = builder.successCode;
        this.topK = builder.topK;
        this.scaleScore = builder.scaleScore;
        this.returnEmbedding = builder.returnEmbedding;
        this.scoreThreshold = builder.scoreThreshold;
        this.groupBy = builder.groupBy;
        this.groupSize = builder.groupSize;
        this.queryEmbedding = builder.queryEmbedding;
        this.querySparseEmbedding = builder.querySparseEmbedding;
        this.documents = builder.documents;
        this.retrievedDocuments = builder.retrievedDocuments;
        this.windowSize = builder.windowSize;
        this.filters = builder.filters;
        this.filterPolicy = builder.filterPolicy;
        this.timeout = builder.timeout;
        this.maxRetries = builder.maxRetries;
        this.customHeaders = builder.customHeaders;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public Integer getSuccessCode() {
        return successCode;
    }

    public Integer getTopK() {
        return topK;
    }

    public Boolean getScaleScore() {
        return scaleScore;
    }

    public Boolean getReturnEmbedding() {
        return returnEmbedding;
    }

    public Double getScoreThreshold() {
        return scoreThreshold;
    }

    public String getGroupBy() {
        return groupBy;
    }

    public Integer getGroupSize() {
        return groupSize;
    }

    public List<Float> getQueryEmbedding() {
        return queryEmbedding;
    }

    public SparseEmbedding getQuerySparseEmbedding() {
        return querySparseEmbedding;
    }

    public List<HayStackDocument> getDocuments() {
        return documents;
    }

    public List<HayStackDocument> getRetrievedDocuments() {
        return retrievedDocuments;
    }

    public Integer getWindowSize() {
        return windowSize;
    }

    public Map<String, Object> getFilters() {
        return filters;
    }

    public FilterPolicy getFilterPolicy() {
        return filterPolicy;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public Integer getMaxRetries() {
        return maxRetries;
    }

    public Map<String, String> getCustomHeaders() {
        return customHeaders;
    }

    public static class Builder {

        private String baseUrl;

        private Integer successCode = DEFAULT_SUCCESS_CODE;

        private Integer topK = DEFAULT_TOP_K;

        private Boolean scaleScore;

        private Boolean returnEmbedding;

        private Double scoreThreshold;

        private String groupBy;

        private Integer groupSize;

        private List<Float> queryEmbedding;

        private SparseEmbedding querySparseEmbedding;

        private List<HayStackDocument> documents;

        private List<HayStackDocument> retrievedDocuments;

        private Integer windowSize;

        private Map<String, Object> filters;

        private FilterPolicy filterPolicy;

        private Duration timeout = DEFAULT_TIMEOUT;

        private Integer maxRetries = DEFAULT_MAX_RETRIES;

        private Map<String, String> customHeaders = new HashMap<>();

        /**
         * Sets the HayStack base URL.
         *
         * @param baseUrl the base URL
         * @return this builder
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the HayStack API success code.
         * <p>Default: 0
         *
         * @param successCode the success code
         * @return this builder
         */
        public Builder successCode(Integer successCode) {
            this.successCode = successCode;
            return this;
        }

        /**
         * The maximum number of documents to return. If using `group_by` parameters, maximum number of groups to return.
         * <p>Default: 3
         *
         * @param topK the number of chunks to retrieve (must be > 0)
         * @return this builder
         */
        public Builder topK(Integer topK) {
            if (topK != null && topK <= 0) {
                throw new IllegalArgumentException("topK must be greater than 0");
            }
            this.topK = topK;
            return this;
        }

        /**
         * Whether to scale the scores of the retrieved documents or not
         *
         * <p>- When `true`, scales the score of retrieved documents to a range of 0 to 1, where 1 means extremely relevant.
         * <p>- When `false`, uses raw similarity scores.
         *
         * @param scaleScore whether to scale scores
         * @return this builder
         */
        public Builder scaleScore(Boolean scaleScore) {
            this.scaleScore = scaleScore;
            return this;
        }

        /**
         * Whether to return the embedding of the retrieved Documents.
         *
         * @param returnEmbedding whether to return embedding
         * @return this builder
         */
        public Builder returnEmbedding(Boolean returnEmbedding) {
            this.returnEmbedding = returnEmbedding;
            return this;
        }

        /**
         * A minimal score threshold for the result.
         * <p>Score of the returned result might be higher or smaller than the threshold depending on the Distance function used.
         * <p>E.g. for cosine similarity only higher scores will be returned.
         *
         * @param scoreThreshold the score threshold
         * @return this builder
         */
        public Builder scoreThreshold(Double scoreThreshold) {
            this.scoreThreshold = scoreThreshold;
            return this;
        }

        /**
         * Payload field to group by, must be a string or number field. If the field contains more than 1 value,
         * all values will be used for grouping. One point can be in multiple groups.
         *
         * @param groupBy group name
         * @return this builder
         */
        public Builder groupBy(String groupBy) {
            this.groupBy = groupBy;
            return this;
        }

        /**
         * Maximum amount of points to return per group.
         *
         * <p>Default is 3.
         *
         * @param groupSize group size
         * @return this builder
         */
        public Builder groupSize(Integer groupSize) {
            this.groupSize = groupSize;
            return this;
        }

        /**
         * Use vector retrieval from the vector store.
         * <p>Normally you should not use it directly, instead use normal `query text` together with Haystack's `Embedders`.
         *
         * @param queryEmbedding queryEmbedding
         * @return this builder
         */
        public Builder queryEmbedding(List<Float> queryEmbedding) {
            this.queryEmbedding = queryEmbedding;
            return this;
        }

        /**
         * Sparse Embedding of the query.
         *
         * @param querySparseEmbedding SparseEmbedding
         * @return this builder
         */
        public Builder querySparseEmbedding(SparseEmbedding querySparseEmbedding) {
            this.querySparseEmbedding = querySparseEmbedding;
            return this;
        }

        public Builder documents(List<HayStackDocument> documents) {
            this.documents = documents;
            return this;
        }

        /**
         * The documents must include `metadata` indicating their origin and position:
         * <p>- `source_id` is used to group sentence chunks belonging to the same original document.
         * <p>- `split_id` represents the position/order of the chunk within the document.
         *
         * @param retrievedDocuments the documents
         * @return this builder
         */
        public Builder retrievedDocuments(List<HayStackDocument> retrievedDocuments) {
            this.retrievedDocuments = retrievedDocuments;
            return this;
        }

        /**
         * The number of adjacent documents to include on each side of the retrieved document can be configured using the `window_size` parameter.
         *
         * @param windowSize the window size
         * @return this builder
         */
        public Builder windowSize(Integer windowSize) {
            this.windowSize = windowSize;
            return this;
        }

        /**
         * A dictionary with filters to narrow down the retriever's search space in the document store.
         *
         * @param filters the filters
         * @return this builder
         */
        public Builder filters(Map<String, Object> filters) {
            this.filters = filters;
            return this;
        }

        /**
         * Policy to determine how filters are applied in retrievers interacting with document stores.
         *
         * @param filterPolicy FilterPolicy.REPLACE or FilterPolicy.MERGE
         * @return this builder
         */
        public Builder filterPolicy(FilterPolicy filterPolicy) {
            this.filterPolicy = filterPolicy;
            return this;
        }

        /**
         * Sets the HTTP timeout.
         *
         * <p>Default: 30 seconds
         *
         * @param timeout the timeout duration
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets the maximum number of retries for failed requests.
         *
         * <p>Default: 3
         *
         * @param maxRetries the max retries (>= 0)
         * @return this builder
         */
        public Builder maxRetries(Integer maxRetries) {
            if (maxRetries != null && maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries cannot be negative");
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Sets custom HTTP headers.
         *
         * @param customHeaders the custom headers map
         * @return this builder
         */
        public Builder customHeaders(Map<String, String> customHeaders) {
            this.customHeaders = new HashMap<>(customHeaders);
            return this;
        }

        /**
         * Adds a custom HTTP header.
         *
         * @param key   the header name
         * @param value the header value
         * @return this builder
         */
        public Builder addCustomHeader(String key, String value) {
            this.customHeaders.put(key, value);
            return this;
        }

        /**
         * Builds the HayStackConfig instance.
         *
         * <p>The {@code baseUrl} parameter is required and must not be {@code null} or blank.
         *
         * @return a new {@link HayStackConfig} instance
         * @throws IllegalArgumentException if the required {@code baseUrl} parameter is missing or blank
         */
        public HayStackConfig build() {
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("BaseUrl key is required");
            }

            return new HayStackConfig(this);
        }
    }
}
