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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for RAGFlow integration.
 *
 * <p>This class holds the configuration for connecting to RAGFlow service, including
 * authentication, endpoint, and retrieval parameters.
 *
 * <p><b>API Reference:</b> This configuration is designed to work with RAGFlow's retrieval API:
 * {@code POST /api/v1/retrieval}
 */
public class RAGFlowConfig {

    private static final int DEFAULT_TOP_K = 1024;

    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.2;

    private static final double DEFAULT_VECTOR_SIMILARITY_WEIGHT = 0.3;

    private static final int DEFAULT_PAGE = 1;

    private static final int DEFAULT_PAGE_SIZE = 30;

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static final int DEFAULT_MAX_RETRIES = 3;

    // Authentication
    private final String apiKey;

    // Endpoint
    private final String baseUrl;

    // Datasets (Knowledge Bases) - array of dataset IDs
    private final List<String> datasetIds;

    // Optional: Filter to specific documents
    private final List<String> documentIds;

    // Retrieval Parameters
    private final Integer topK;

    private final Double similarityThreshold;

    private final Double vectorSimilarityWeight;

    private final Integer page;

    private final Integer pageSize;

    private final Boolean useKg;

    private final Boolean tocEnhance;

    private final Integer rerankId;

    private final Boolean keyword;

    private final Boolean highlight;

    private final List<String> crossLanguages;

    private final Map<String, Object> metadataCondition;

    // HTTP Client Settings
    private final Duration timeout;

    private final Integer maxRetries;

    private final Map<String, String> customHeaders;

    private RAGFlowConfig(Builder builder) {
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.datasetIds = builder.datasetIds;
        this.documentIds = builder.documentIds;
        this.topK = builder.topK;
        this.similarityThreshold = builder.similarityThreshold;
        this.vectorSimilarityWeight = builder.vectorSimilarityWeight;
        this.page = builder.page;
        this.pageSize = builder.pageSize;
        this.useKg = builder.useKg;
        this.tocEnhance = builder.tocEnhance;
        this.rerankId = builder.rerankId;
        this.keyword = builder.keyword;
        this.highlight = builder.highlight;
        this.crossLanguages = builder.crossLanguages;
        this.metadataCondition = builder.metadataCondition;
        this.timeout = builder.timeout;
        this.maxRetries = builder.maxRetries;
        this.customHeaders = builder.customHeaders;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Gets the dataset IDs.
     *
     * <p>These IDs are used in the API request body: {@code "dataset_ids": ["id1", "id2"]}
     *
     * @return the list of dataset IDs
     */
    public List<String> getDatasetIds() {
        return datasetIds;
    }

    /**
     * Gets the document IDs to filter retrieval.
     *
     * <p>Optional: If specified, only search within these documents.
     *
     * @return the list of document IDs, or null if not set
     */
    public List<String> getDocumentIds() {
        return documentIds;
    }

    /**
     * Gets the top K value for retrieval.
     *
     * <p>Default value: 5 (as per RAGFlow API documentation)
     *
     * @return the top K value
     */
    public Integer getTopK() {
        return topK;
    }

    /**
     * Gets the similarity threshold for filtering results.
     *
     * <p>Default value: 0.2 (as per RAGFlow API documentation)
     *
     * @return the similarity threshold (0.0-1.0)
     */
    public Double getSimilarityThreshold() {
        return similarityThreshold;
    }

    /**
     * Gets the vector similarity weight.
     *
     * <p>The weight of vector cosine similarity. If x represents the weight,
     * then (1 - x) is the term similarity weight.
     *
     * <p>Default value: 0.3
     *
     * @return the vector similarity weight (0.0-1.0)
     */
    public Double getVectorSimilarityWeight() {
        return vectorSimilarityWeight;
    }

    /**
     * Gets the page number for pagination.
     *
     * <p>Default value: 1
     *
     * @return the page number
     */
    public Integer getPage() {
        return page;
    }

    /**
     * Gets the page size for pagination.
     *
     * <p>Default value: 30
     *
     * @return the page size
     */
    public Integer getPageSize() {
        return pageSize;
    }

    /**
     * Gets whether to use knowledge graph for multi-hop queries.
     *
     * <p>Default value: false
     *
     * @return true if knowledge graph is enabled
     */
    public Boolean getUseKg() {
        return useKg;
    }

    /**
     * Gets whether to use TOC (Table of Contents) enhancement.
     *
     * <p>Default value: false
     *
     * @return true if TOC enhancement is enabled
     */
    public Boolean getTocEnhance() {
        return tocEnhance;
    }

    /**
     * Gets the rerank model ID.
     *
     * @return the rerank model ID, or null if not set
     */
    public Integer getRerankId() {
        return rerankId;
    }

    /**
     * Gets whether keyword-based matching is enabled.
     *
     * <p>Default value: false
     *
     * @return true if keyword matching is enabled
     */
    public Boolean getKeyword() {
        return keyword;
    }

    /**
     * Gets whether highlighting of matched terms is enabled.
     *
     * <p>Default value: false
     *
     * @return true if highlighting is enabled
     */
    public Boolean getHighlight() {
        return highlight;
    }

    /**
     * Gets the list of languages for cross-language retrieval.
     *
     * @return the list of target languages, or null if not set
     */
    public List<String> getCrossLanguages() {
        return crossLanguages;
    }

    /**
     * Gets the metadata filtering conditions.
     *
     * <p>This is used to filter chunks based on their metadata fields. For example:
     *
     * <pre>{@code
     * Map<String, Object> condition = Map.of(
     *     "author", "John",
     *     "category", "tech"
     * );
     * }</pre>
     *
     * @return the metadata condition map, or null if not set
     */
    public Map<String, Object> getMetadataCondition() {
        return metadataCondition;
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

        private String apiKey;

        private String baseUrl;

        private List<String> datasetIds = new ArrayList<>();

        private List<String> documentIds;

        private Integer topK = DEFAULT_TOP_K;

        private Double similarityThreshold = DEFAULT_SIMILARITY_THRESHOLD;

        private Double vectorSimilarityWeight = DEFAULT_VECTOR_SIMILARITY_WEIGHT;

        private Integer page = DEFAULT_PAGE;

        private Integer pageSize = DEFAULT_PAGE_SIZE;

        private Boolean useKg;

        private Boolean tocEnhance;

        private Integer rerankId;

        private Boolean keyword;

        private Boolean highlight;

        private List<String> crossLanguages;

        private Map<String, Object> metadataCondition;

        private Duration timeout = DEFAULT_TIMEOUT;

        private Integer maxRetries = DEFAULT_MAX_RETRIES;

        private Map<String, String> customHeaders = new HashMap<>();

        /**
         * Sets the RAGFlow API key.
         *
         * @param apiKey the API key (required)
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the RAGFlow base URL.
         *
         * <p>Default: http://localhost:9380
         *
         * @param baseUrl the base URL
         * @return this builder
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the dataset IDs (replaces existing list).
         *
         * <p>These are the IDs of RAGFlow datasets to search in. You can find these IDs in the
         * RAGFlow console.
         *
         * @param datasetIds the list of dataset IDs (required, at least one)
         * @return this builder
         */
        public Builder datasetIds(List<String> datasetIds) {
            this.datasetIds = new ArrayList<>(datasetIds);
            return this;
        }

        /**
         * Adds a single dataset ID to the list.
         *
         * @param datasetId the dataset ID to add
         * @return this builder
         */
        public Builder addDatasetId(String datasetId) {
            this.datasetIds.add(datasetId);
            return this;
        }

        /**
         * Sets the document IDs to filter retrieval (optional).
         *
         * <p>If specified, only search within these documents.
         *
         * @param documentIds the list of document IDs
         * @return this builder
         */
        public Builder documentIds(List<String> documentIds) {
            this.documentIds = documentIds != null ? new ArrayList<>(documentIds) : null;
            return this;
        }

        /**
         * Adds a single document ID to the filter list.
         *
         * @param documentId the document ID to add
         * @return this builder
         */
        public Builder addDocumentId(String documentId) {
            if (this.documentIds == null) {
                this.documentIds = new ArrayList<>();
            }
            this.documentIds.add(documentId);
            return this;
        }

        /**
         * Sets the top K value for retrieval.
         *
         * <p>Default: 5
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
         * Sets the similarity threshold.
         *
         * <p>Only chunks with similarity >= threshold will be returned.
         *
         * <p>Default: 0.2
         *
         * @param similarityThreshold the similarity threshold (0.0-1.0)
         * @return this builder
         */
        public Builder similarityThreshold(Double similarityThreshold) {
            if (similarityThreshold != null
                    && (similarityThreshold < 0.0 || similarityThreshold > 1.0)) {
                throw new IllegalArgumentException(
                        "similarityThreshold must be between 0.0 and 1.0");
            }
            this.similarityThreshold = similarityThreshold;
            return this;
        }

        /**
         * Sets the vector similarity weight.
         *
         * <p>The weight of vector cosine similarity. If x represents the weight,
         * then (1 - x) is the term similarity weight.
         *
         * <p>Default: 0.3
         *
         * @param vectorSimilarityWeight the weight (0.0-1.0)
         * @return this builder
         */
        public Builder vectorSimilarityWeight(Double vectorSimilarityWeight) {
            if (vectorSimilarityWeight != null
                    && (vectorSimilarityWeight < 0.0 || vectorSimilarityWeight > 1.0)) {
                throw new IllegalArgumentException(
                        "vectorSimilarityWeight must be between 0.0 and 1.0");
            }
            this.vectorSimilarityWeight = vectorSimilarityWeight;
            return this;
        }

        /**
         * Sets the page number for pagination.
         *
         * <p>Default: 1
         *
         * @param page the page number (>= 1)
         * @return this builder
         */
        public Builder page(Integer page) {
            if (page != null && page < 1) {
                throw new IllegalArgumentException("page must be >= 1");
            }
            this.page = page;
            return this;
        }

        /**
         * Sets the page size for pagination.
         *
         * <p>Default: 30
         *
         * @param pageSize the page size (>= 1)
         * @return this builder
         */
        public Builder pageSize(Integer pageSize) {
            if (pageSize != null && pageSize < 1) {
                throw new IllegalArgumentException("pageSize must be >= 1");
            }
            this.pageSize = pageSize;
            return this;
        }

        /**
         * Sets whether to use knowledge graph for multi-hop queries.
         *
         * <p>Before enabling this, ensure you have successfully constructed
         * a knowledge graph for the specified datasets.
         *
         * <p>Default: false
         *
         * @param useKg true to enable knowledge graph
         * @return this builder
         */
        public Builder useKg(Boolean useKg) {
            this.useKg = useKg;
            return this;
        }

        /**
         * Sets whether to use TOC (Table of Contents) enhancement.
         *
         * <p>Before enabling this, ensure you have enabled TOC_Enhance and
         * successfully extracted table of contents for the specified datasets.
         *
         * <p>Default: false
         *
         * @param tocEnhance true to enable TOC enhancement
         * @return this builder
         */
        public Builder tocEnhance(Boolean tocEnhance) {
            this.tocEnhance = tocEnhance;
            return this;
        }

        /**
         * Sets the rerank model ID.
         *
         * @param rerankId the rerank model ID
         * @return this builder
         */
        public Builder rerankId(Integer rerankId) {
            this.rerankId = rerankId;
            return this;
        }

        /**
         * Sets whether to enable keyword-based matching.
         *
         * <p>Default: false
         *
         * @param keyword true to enable keyword matching
         * @return this builder
         */
        public Builder keyword(Boolean keyword) {
            this.keyword = keyword;
            return this;
        }

        /**
         * Sets whether to enable highlighting of matched terms in results.
         *
         * <p>Default: false
         *
         * @param highlight true to enable highlighting
         * @return this builder
         */
        public Builder highlight(Boolean highlight) {
            this.highlight = highlight;
            return this;
        }

        /**
         * Sets the languages for cross-language retrieval.
         *
         * <p>The languages that should be translated into, in order to achieve
         * keywords retrievals in different languages.
         *
         * @param crossLanguages the list of target languages
         * @return this builder
         */
        public Builder crossLanguages(List<String> crossLanguages) {
            this.crossLanguages = crossLanguages != null ? new ArrayList<>(crossLanguages) : null;
            return this;
        }

        /**
         * Adds a language for cross-language retrieval.
         *
         * @param language the target language to add
         * @return this builder
         */
        public Builder addCrossLanguage(String language) {
            if (this.crossLanguages == null) {
                this.crossLanguages = new ArrayList<>();
            }
            this.crossLanguages.add(language);
            return this;
        }

        /**
         * Sets the metadata filtering condition.
         *
         * <p>Use this to filter chunks based on their metadata. For example:
         *
         * <pre>{@code
         * builder.metadataCondition(Map.of(
         *     "author", "John",
         *     "category", "tech"
         * ));
         * }</pre>
         *
         * @param metadataCondition the metadata condition map
         * @return this builder
         */
        public Builder metadataCondition(Map<String, Object> metadataCondition) {
            this.metadataCondition =
                    metadataCondition != null ? new HashMap<>(metadataCondition) : null;
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
         * @param key the header name
         * @param value the header value
         * @return this builder
         */
        public Builder addCustomHeader(String key, String value) {
            this.customHeaders.put(key, value);
            return this;
        }

        /**
         * Builds the RAGFlowConfig instance.
         *
         * <p>Either dataset_ids or document_ids must be set (or both).
         * If document_ids is set without dataset_ids, ensure all documents
         * use the same embedding model.
         *
         * @return a new RAGFlowConfig instance
         * @throws IllegalArgumentException if required parameters are missing
         */
        public RAGFlowConfig build() {
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("BaseUrl key is required");
            }
            if (apiKey == null || apiKey.trim().isEmpty()) {
                throw new IllegalArgumentException("API key is required");
            }
            boolean hasDatasetIds = datasetIds != null && !datasetIds.isEmpty();
            boolean hasDocumentIds = documentIds != null && !documentIds.isEmpty();
            if (!hasDatasetIds && !hasDocumentIds) {
                throw new IllegalArgumentException(
                        "Either dataset_ids or document_ids must be set. Use"
                                + " addDatasetId()/datasetIds() or addDocumentId()/documentIds()");
            }
            return new RAGFlowConfig(this);
        }
    }
}
