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

import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * RAGFlow Knowledge Base implementation.
 *
 * <p>This class provides integration with RAGFlow Knowledge Base service, implementing
 * the Knowledge interface to enable seamless RAG (Retrieval-Augmented Generation)
 * capabilities with RAGFlow-hosted knowledge bases.
 *
 * <p>Key features:
 * <ul>
 *   <li>Local or remote RAGFlow deployment support
 *   <li>Advanced document understanding with OCR
 *   <li>Knowledge graph enhanced retrieval
 *   <li>Reranking support for better results
 *   <li>Conversation history support for context-aware retrieval
 *   <li>Compatible with Bailian and Dify RAG plugins (same Knowledge interface)
 * </ul>
 *
 * <p><b>Functional Scope:</b> This plugin focuses on retrieval capabilities only,
 * similar to Bailian and Dify RAG plugins. Document management (upload/delete/update)
 * should be performed via RAGFlow console.
 *
 * <p><b>Example usage:</b>
 * <pre>{@code
 * RAGFlowConfig config = RAGFlowConfig.builder()
 *     .apiKey("ragflow-xxx")
 *     .baseUrl("http://localhost:9380")
 *     .knowledgeBaseId("kb-xxx")
 *     .topK(10)
 *     .similarityThreshold(0.5)
 *     .enableRerank(true)
 *     .build();
 *
 * RAGFlowKnowledge knowledge = RAGFlowKnowledge.builder()
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
 * Please use RAGFlow console to upload, delete, and manage documents. This design
 * ensures compatibility with Bailian and Dify RAG plugins and keeps the plugin focused.
 *
 * @author RAGFlow Integration Team
 */
public class RAGFlowKnowledge implements Knowledge {

    private static final Logger logger = LoggerFactory.getLogger(RAGFlowKnowledge.class);

    private final RAGFlowClient client;

    private final RAGFlowConfig config;

    private RAGFlowKnowledge(Builder builder) {
        this.config = builder.config;
        this.client = builder.client != null ? builder.client : new RAGFlowClient(config);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Retrieve documents from the RAGFlow knowledge base.
     *
     * <p>This method searches the RAGFlow dataset for documents relevant to the given query. The
     * RAGFlow retrieve-chunks API is used under the hood.
     *
     * <p><b>API Endpoint:</b> {@code POST /api/v1/datasets/{dataset_id}/retrieve-chunks}
     *
     * <p><b>Features:</b>
     *
     * <ul>
     *   <li>Vector similarity search
     *   <li>Configurable top-K results
     *   <li>Similarity threshold filtering
     *   <li>Metadata-based filtering (via config)
     * </ul>
     *
     * <p><b>Note:</b> Unlike some other RAG systems, RAGFlow's retrieve-chunks API does not
     * support:
     *
     * <ul>
     *   <li>❌ Conversation history for context-aware retrieval
     *   <li>❌ Built-in reranking
     * </ul>
     *
     * @param query the query text (required)
     * @param config the retrieval configuration (limit, score threshold)
     * @return a Mono emitting the list of retrieved documents, sorted by relevance
     */
    @Override
    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
        if (query == null || query.trim().isEmpty()) {
            logger.warn("Empty query provided, returning empty result");
            return Mono.just(new ArrayList<>());
        }

        logger.debug("RAGFlow retrieve: query={}, config={}", query, config);

        // Extract retrieval parameters
        Integer topK = config != null ? config.getLimit() : null;
        Double similarityThreshold = config != null ? config.getScoreThreshold() : null;

        // Call RAGFlow API (metadata condition from config)
        return client.retrieve(query, topK, similarityThreshold, null)
                .map(
                        response -> {
                            if (response.getData() == null
                                    || response.getData().getChunks() == null) {
                                logger.warn("RAGFlow returned empty response for query: {}", query);
                                return new ArrayList<Document>();
                            }

                            List<Document> documents =
                                    RAGFlowDocumentConverter.convertToDocuments(
                                            response.getData().getChunks());

                            logger.debug(
                                    "RAGFlow retrieved {} documents for query: {}",
                                    documents.size(),
                                    query);

                            return documents;
                        })
                .onErrorResume(
                        e -> {
                            logger.error("RAGFlow retrieval failed for query: {}", query, e);
                            return Mono.error(e);
                        });
    }

    /**
     * Adds documents to the knowledge base.
     *
     * <p><b>Note:</b> This operation is not supported, consistent with Bailian and Dify RAG
     * plugins. Document management should be performed via RAGFlow console:
     * <ol>
     *   <li>Access RAGFlow console (http://localhost:9380)
     *   <li>Navigate to Knowledge Base > Select your dataset
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
                                + "Please use RAGFlow console to manage documents. "
                                + "This design keeps the plugin focused on retrieval and "
                                + "compatible with Bailian and Dify RAG plugins."));
    }

    public static class Builder {

        private RAGFlowConfig config;

        private RAGFlowClient client;

        public Builder config(RAGFlowConfig config) {
            this.config = config;
            return this;
        }

        public Builder client(RAGFlowClient client) {
            this.client = client;
            return this;
        }

        public RAGFlowKnowledge build() {
            if (config == null) {
                throw new IllegalArgumentException("RAGFlowConfig is required");
            }
            return new RAGFlowKnowledge(this);
        }
    }
}
