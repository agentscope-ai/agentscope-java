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

import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Dify Cloud Knowledge Base implementation.
 *
 * <p>This class provides integration with Dify Knowledge Base service, implementing
 * the Knowledge interface to enable seamless RAG (Retrieval-Augmented Generation)
 * capabilities with Dify-hosted or self-hosted knowledge bases.
 *
 * <p>Key features:
 * <ul>
 *   <li>Cloud-hosted or self-hosted Dify deployment support
 *   <li>Multiple retrieval modes (keyword, semantic, hybrid, fulltext)
 *   <li>Advanced features like reranking and score filtering
 *   <li>Conversation history support for context-aware retrieval
 *   <li>Compatible with Bailian RAG plugin (same Knowledge interface)
 * </ul>
 *
 * <p><b>Functional Scope:</b> This plugin focuses on retrieval capabilities only,
 * similar to Bailian RAG plugin. Document management (upload/delete/update) should
 * be performed via Dify console.
 *
 * <p>Example usage:
 * <pre>{@code
 * // 1. Configure Dify connection
 * DifyRAGConfig config = DifyRAGConfig.builder()
 *     .apiKey(System.getenv("DIFY_RAG_API_KEY"))
 *     .datasetId("your-dataset-id")  // Configure in code
 *     .retrievalMode(RetrievalMode.HYBRID_SEARCH)
 *     .enableRerank(true)
 *     .build();
 *
 * // 2. Create knowledge base instance
 * DifyKnowledge knowledge = DifyKnowledge.builder()
 *     .config(config)
 *     .build();
 *
 * // 3. Retrieve documents
 * RetrieveConfig retrieveConfig = RetrieveConfig.builder()
 *     .limit(5)
 *     .scoreThreshold(0.5)
 *     .build();
 *
 * List<Document> results = knowledge.retrieve("query text", retrieveConfig).block();
 *
 * // 4. Use with agents via built-in RAG configuration
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(chatModel)
 *     .knowledge(knowledge)
 *     .ragMode(RAGMode.AGENTIC)
 *     .build();
 * }</pre>
 *
 * <p><b>Note:</b> Document management is not supported via this plugin.
 * Please use Dify console to upload, delete, and manage documents. This design
 * ensures compatibility with Bailian RAG plugin and keeps the plugin focused.
 */
public class DifyKnowledge implements Knowledge {

    private static final Logger log = LoggerFactory.getLogger(DifyKnowledge.class);

    private final DifyRAGClient client;
    private final DifyRAGConfig config;

    /**
     * Creates a new DifyKnowledge instance.
     *
     * @param client the Dify API client
     * @param config the Dify configuration (can be null if client was created separately)
     */
    private DifyKnowledge(DifyRAGClient client, DifyRAGConfig config) {
        if (client == null) {
            throw new IllegalArgumentException("DifyRAGClient cannot be null");
        }

        this.client = client;
        this.config = config;

        log.info("DifyKnowledge initialized for dataset: {}", client.getConfig().getDatasetId());
    }

    /**
     * Adds documents to the knowledge base.
     *
     * <p><b>Note:</b> This operation is not supported, consistent with Bailian RAG plugin.
     * Document management should be performed via Dify console:
     * <ol>
     *   <li>Log in to Dify console (https://cloud.dify.ai)
     *   <li>Navigate to Knowledge > Select your dataset
     *   <li>Click "Upload Document" to add files
     *   <li>Wait for processing to complete
     *   <li>Documents will be available for retrieval via this plugin
     * </ol>
     *
     * @param documents the list of documents to add
     * @return a Mono that completes with an error indicating the operation is not supported
     */
    @Override
    public Mono<Void> addDocuments(List<Document> documents) {
        return Mono.error(
                new UnsupportedOperationException(
                        "Document upload is not supported via this plugin. "
                                + "Please use Dify console to manage documents. "
                                + "This design keeps the plugin focused on retrieval and "
                                + "compatible with Bailian RAG plugin."));
    }

    /**
     * Retrieves relevant documents based on a query.
     *
     * <p>This method searches the Dify knowledge base for documents relevant to
     * the given query. The query is processed according to the configured retrieval
     * mode (keyword/semantic/hybrid/fulltext), and results are filtered by score
     * threshold and limited as specified.
     *
     * <p>Dify's retrieve API features:
     * <ul>
     *   <li>Multiple retrieval modes for different use cases
     *   <li>Automatic query embedding (for semantic/hybrid modes)
     *   <li>Vector similarity search with configurable algorithms
     *   <li>Optional reranking for improved relevance
     *   <li>Metadata filtering support
     * </ul>
     *
     * <p>The retrieval process:
     * <ol>
     *   <li>Query is sent to Dify API with configured parameters
     *   <li>Dify performs search according to retrieval mode
     *   <li>Results are optionally reranked if enabled
     *   <li>Documents are filtered by score threshold
     *   <li>Top-K documents are returned, sorted by relevance
     * </ol>
     *
     * @param query the search query text
     * @param config the retrieval configuration (limit, score threshold)
     * @return a Mono that emits a list of relevant Document objects, sorted by relevance
     */
    @Override
    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
        if (query == null) {
            return Mono.error(new IllegalArgumentException("Query cannot be null"));
        }
        if (config == null) {
            return Mono.error(new IllegalArgumentException("RetrieveConfig cannot be null"));
        }
        if (query.trim().isEmpty()) {
            log.debug("Empty query received, returning empty result");
            return Mono.just(List.of());
        }

        log.debug(
                "Retrieving documents for query: '{}' (limit: {}, threshold: {})",
                truncate(query, 50),
                config.getLimit(),
                config.getScoreThreshold());

        return client.retrieve(query, config.getLimit())
                .map(DifyDocumentConverter::fromDifyResponse)
                .map(
                        documents ->
                                documents.stream()
                                        .filter(
                                                doc ->
                                                        doc.getScore() != null
                                                                && doc.getScore()
                                                                        >= config
                                                                                .getScoreThreshold())
                                        .sorted(
                                                Comparator.comparing(
                                                        Document::getScore,
                                                        Comparator.reverseOrder()))
                                        .limit(config.getLimit())
                                        .collect(Collectors.toList()))
                .doOnSuccess(
                        docs ->
                                log.debug(
                                        "Successfully retrieved {} documents (after filtering)",
                                        docs.size()))
                .doOnError(error -> log.error("Failed to retrieve documents from Dify", error));
    }

    /**
     * Gets the Dify API client.
     *
     * @return the client
     */
    public DifyRAGClient getClient() {
        return client;
    }

    /**
     * Gets the Dify configuration.
     *
     * @return the configuration, or null if not available
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
     * Creates a new builder for DifyKnowledge.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for DifyKnowledge.
     */
    public static class Builder {
        private DifyRAGConfig config;
        private DifyRAGClient client;

        private Builder() {}

        /**
         * Sets the Dify configuration.
         *
         * <p>If this is set, a DifyRAGClient will be automatically created from the config.
         * You cannot set both config and client - choose one approach.
         *
         * @param config the Dify configuration
         * @return this builder for method chaining
         */
        public Builder config(DifyRAGConfig config) {
            this.config = config;
            return this;
        }

        /**
         * Sets the Dify client directly.
         *
         * <p>Use this if you want to share a client instance across multiple
         * DifyKnowledge instances. You cannot set both config and client.
         *
         * @param client the Dify client
         * @return this builder for method chaining
         */
        public Builder client(DifyRAGClient client) {
            this.client = client;
            return this;
        }

        /**
         * Builds a new DifyKnowledge instance.
         *
         * @return a new DifyKnowledge instance
         * @throws IllegalArgumentException if required parameters are missing
         * @throws IllegalStateException if both config and client are set
         */
        public DifyKnowledge build() {
            // Validate that only one of config or client is set
            if (config != null && client != null) {
                throw new IllegalStateException(
                        "Cannot set both config and client. Choose one approach.");
            }

            // Create client from config if needed
            DifyRAGClient finalClient = client;
            if (finalClient == null) {
                if (config == null) {
                    throw new IllegalArgumentException("Either config or client must be provided");
                }
                finalClient = new DifyRAGClient(config);
            }

            return new DifyKnowledge(finalClient, config);
        }
    }
}
