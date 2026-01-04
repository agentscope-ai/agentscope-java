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

import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.integration.haystack.model.HayStackDocument;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * HayStack RAG Knowledge Base implementation.
 *
 * <p>This class provides integration with HayStack Knowledge Base service, implementing
 * the Knowledge interface to enable seamless RAG (Retrieval-Augmented Generation)
 * capabilities with HayStack-hosted knowledge bases.
 *
 * <p>Key features:
 * <ul>
 *   <li>Built on HayStack RAG with end-to-end retrieval, ranking, and generation pipelines
 *   <li>Local or remote HayStack deployment support
 *   <li>Compatible with Bailian and Dify RAG plugins (same Knowledge interface)
 * </ul>
 *
 * <p><b>Functional Scope:</b> This plugin focuses on retrieval capabilities only,
 * similar to Bailian and Dify RAG plugins. Document management (upload/delete/update)
 * should be performed via HayStack RAG server.
 *
 * <p><b>Example usage:</b>
 * <pre>{@code
 * HayStackConfig config = HayStackConfig.builder()
 *     .baseUrl("http://localhost:8080")
 *     .topK(10)
 *     .build();
 *
 * HayStackKnowledge knowledge = HayStackKnowledge.builder()
 *     .config(config)
 *     .build();
 *
 * // Retrieve documents
 * List<Document> docs = knowledge.retrieve("What is AI?", retrieveConfig).block();
 *
 * // Use with Agent
 * ReActAgent agent = ReActAgent.builder()
 *     .knowledge(knowledge)
 *     .ragMode(RAGMode.AGENTIC)
 *     .build();
 * }</pre>
 *
 * <p><b>Note:</b> Document management is not supported via this plugin.
 * Please use HayStack server to upload, delete, and manage documents. This design
 * ensures compatibility with Bailian and Dify RAG plugins and keeps the plugin focused.
 */
public class HayStackKnowledge implements Knowledge {

    private static final Logger logger = LoggerFactory.getLogger(HayStackKnowledge.class);

    private final HayStackClient client;

    private final HayStackConfig config;

    private HayStackKnowledge(Builder builder) {
        this.config = builder.config;
        this.client = builder.client != null ? builder.client : new HayStackClient(config);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Adds documents to the Haystack knowledge base.
     *
     * <p><b>Note:</b> This operation is not supported.
     * Document ingestion and indexing are managed by the Haystack service itself.
     *
     * <p>To add or update documents:
     * <ol>
     *   <li>Prepare source files in the configured data directory
     *   <li>Restart or re-run the Haystack indexing pipeline
     *   <li>Documents will be indexed into the document store (e.g. Chroma)
     *   <li>Indexed documents become available for retrieval via this API
     * </ol>
     *
     * <p>This design keeps document management and retrieval responsibilities
     * clearly separated and avoids inconsistent indexing states.
     *
     * @param documents the list of documents to add
     * @return a Mono that completes with an error indicating the operation is not supported
     */
    @Override
    public Mono<Void> addDocuments(List<Document> documents) {
        return Mono.error(
                new UnsupportedOperationException(
                        "Document upload is not supported via this plugin. "
                                + "Please use HayStack to manage documents. "
                                + "This design keeps the plugin focused on retrieval and "
                                + "compatible with Bailian RAG plugin."));
    }

    /**
     * Retrieve documents from the HayStack knowledge base.
     *
     * <p>This method searches the HayStack dataset for documents relevant to the given query.
     * The results support filtering by score.
     *
     * <p><b>Features:</b>
     *
     * <ul>
     *   <li>Vector similarity search
     *   <li>Retrieve the context surrounding relevant sentences.
     *   <li>Configurable top-K results
     *   <li>Similarity threshold filtering
     * </ul>
     *
     * <p><b>Note:</b> Unlike some other RAG systems, HayStack RAG's retrieve-document API does not support:
     *
     * <ul>
     *   <li>❌ Conversation history for context-aware retrieval
     *   <li>❌ Built-in reranking
     * </ul>
     *
     * @param query  the query text (required)
     * @param config the retrieval configuration (limit, score)
     * @return a Mono emitting the list of retrieved documents, sorted by relevance
     */
    @Override
    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
        if (query == null) {
            return Mono.error(new IllegalArgumentException("Query cannot be null or empty"));
        }

        if (config == null) {
            return Mono.error(new IllegalArgumentException("RetrieveConfig cannot be null"));
        }

        if (query.trim().isEmpty()) {
            logger.debug("Empty query received, returning empty result");
            return Mono.just(List.of());
        }

        logger.debug(
                "Retrieving documents for query: '{}' (limit: {}, threshold: {})",
                query,
                config.getLimit(),
                config.getScoreThreshold());

        // Extract retrieval parameters
        Integer topK = config.getLimit() > 0 ? config.getLimit() : null;

        Double scoreThreshold = config.getScoreThreshold() > 0 ? config.getScoreThreshold() : null;

        // Call HayStack API
        return client.retrieve(query, topK, scoreThreshold)
                .map(
                        response -> {
                            if (response.getDocuments() == null
                                    && response.getContextDocuments() == null) {
                                logger.warn(
                                        "HayStack returned empty response for query: {}", query);
                                return new ArrayList<Document>();
                            }

                            // Prioritize taking from documents, otherwise from contextDocuments
                            List<HayStackDocument> sourceDocs =
                                    response.getDocuments() != null
                                            ? response.getDocuments()
                                            : response.getContextDocuments();

                            List<Document> documents =
                                    HayStackDocumentConverter.convertToDocuments(sourceDocs);

                            logger.debug(
                                    "HayStack retrieved {} documents for query: {}",
                                    documents.size(),
                                    query);

                            return documents;
                        })
                .map(
                        documents ->
                                documents.stream()
                                        .filter(
                                                doc -> {
                                                    // When using SentenceWindowRetriever (no score
                                                    // returned), skip filtering.
                                                    if (doc.getScore() == null) return true;
                                                    return doc.getScore()
                                                            >= config.getScoreThreshold();
                                                })
                                        .toList())
                .onErrorResume(
                        e -> {
                            logger.error("HayStack retrieval failed for query: {}", query, e);
                            return Mono.error(e);
                        });
    }

    public static class Builder {
        private HayStackConfig config;
        private HayStackClient client;

        public Builder config(HayStackConfig config) {
            this.config = config;
            return this;
        }

        public Builder client(HayStackClient client) {
            this.client = client;
            return this;
        }

        public HayStackKnowledge build() {
            if (config == null) {
                throw new IllegalArgumentException("HayStackConfig is required");
            }

            return new HayStackKnowledge(this);
        }
    }
}
