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
package io.agentscope.core.rag.knowledge;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.store.VDBStoreBase;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Simple implementation of a knowledge base.
 *
 * <p>This implementation integrates an embedding model and a vector store to provide
 * a complete RAG (Retrieval-Augmented Generation) knowledge base. It handles the
 * full workflow: document embedding, storage, and retrieval.
 *
 * <p>Workflow:
 * <ul>
 *   <li><b>addDocuments:</b> Embed documents → Store documents (with metadata/payload) in vector store
 *   <li><b>retrieve:</b> Embed query → Search documents → Filter by threshold → Return documents
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * EmbeddingModel embeddingModel = DashScopeTextEmbedding.builder()
 *     .apiKey(apiKey)
 *     .modelName("text-embedding-v3")
 *     .dimensions(1024)
 *     .build();
 * VDBStoreBase vectorStore = InMemoryStore.builder().dimensions(1024).build();
 *
 * SimpleKnowledge knowledge = SimpleKnowledge.builder()
 *     .embeddingModel(embeddingModel)
 *     .embeddingStore(vectorStore)
 *     .build();
 *
 * // Add documents
 * List<Document> documents = reader.read(input).block();
 * knowledge.addDocuments(documents).block();
 *
 * // Retrieve documents
 * RetrieveConfig config = RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build();
 * List<Document> results = knowledge.retrieve("query text", config).block();
 * }</pre>
 */
public class SimpleKnowledge implements Knowledge {

    private static final Logger log = LoggerFactory.getLogger(SimpleKnowledge.class);

    private final EmbeddingModel embeddingModel;
    private final VDBStoreBase embeddingStore;

    /**
     * Creates a new SimpleKnowledge instance.
     *
     * @param embeddingModel the embedding model to use for generating vectors
     * @param embeddingStore the vector store to use for storage and search
     * @throws IllegalArgumentException if any parameter is null
     */
    private SimpleKnowledge(EmbeddingModel embeddingModel, VDBStoreBase embeddingStore) {
        if (embeddingModel == null) {
            throw new IllegalArgumentException("Embedding model cannot be null");
        }
        if (embeddingStore == null) {
            throw new IllegalArgumentException("Embedding store cannot be null");
        }
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    @Override
    public Mono<Void> addDocuments(List<Document> documents) {
        if (documents == null) {
            return Mono.error(new IllegalArgumentException("Documents list cannot be null"));
        }
        if (documents.isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(documents)
                .flatMap(
                        doc -> {
                            // Extract ContentBlock from document metadata
                            ContentBlock contentBlock = extractContentBlock(doc.getMetadata());
                            if (contentBlock == null) {
                                log.warn(
                                        "Cannot extract ContentBlock from document: {}",
                                        doc.getId());
                                return Mono.empty();
                            }
                            // Generate embedding for document content
                            return embeddingModel
                                    .embed(contentBlock)
                                    .doOnNext(embedding -> doc.setEmbedding(embedding))
                                    .thenReturn(doc);
                        })
                .collectList()
                .flatMap(
                        docsWithEmbeddings -> {
                            // Batch store documents in vector store (includes metadata/payload)
                            if (docsWithEmbeddings.isEmpty()) {
                                return Mono.empty();
                            }
                            return embeddingStore.add(docsWithEmbeddings);
                        })
                .doOnError(error -> log.error("Failed to add documents to knowledge base", error));
    }

    @Override
    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
        if (query == null) {
            return Mono.error(new IllegalArgumentException("Query cannot be null"));
        }
        if (config == null) {
            return Mono.error(new IllegalArgumentException("RetrieveConfig cannot be null"));
        }
        if (query.trim().isEmpty()) {
            return Mono.just(new ArrayList<>());
        }

        // Convert query string to TextBlock
        TextBlock queryBlock = TextBlock.builder().text(query).build();
        return embeddingModel
                .embed(queryBlock)
                .flatMap(
                        queryEmbedding ->
                                embeddingStore.search(queryEmbedding, config.getLimit(), null))
                .flatMap(
                        results ->
                                Flux.fromIterable(results)
                                        .filter(
                                                doc ->
                                                        doc != null
                                                                && doc.getScore() != null
                                                                && doc.getScore()
                                                                        >= config
                                                                                .getScoreThreshold())
                                        .sort(
                                                Comparator.comparing(
                                                        Document::getScore,
                                                        Comparator.reverseOrder()))
                                        .collectList());
    }

    /**
     * Gets the embedding model used by this knowledge base.
     *
     * @return the embedding model
     */
    public EmbeddingModel getEmbeddingModel() {
        return embeddingModel;
    }

    /**
     * Gets the vector store used by this knowledge base.
     *
     * @return the vector store
     */
    public VDBStoreBase getEmbeddingStore() {
        return embeddingStore;
    }

    /**
     * Extracts a ContentBlock from DocumentMetadata.
     *
     * <p>Since DocumentMetadata now directly stores ContentBlock, this method simply returns it.
     *
     * @param metadata the document metadata
     * @return ContentBlock from metadata, or null if metadata is null or content is not available
     */
    private ContentBlock extractContentBlock(DocumentMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        return metadata.getContent();
    }

    /**
     * Creates a new builder for SimpleKnowledge.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for SimpleKnowledge.
     */
    public static class Builder {
        private EmbeddingModel embeddingModel;
        private VDBStoreBase embeddingStore;

        private Builder() {}

        /**
         * Sets the embedding model.
         *
         * @param embeddingModel the embedding model to use
         * @return this builder for method chaining
         */
        public Builder embeddingModel(EmbeddingModel embeddingModel) {
            this.embeddingModel = embeddingModel;
            return this;
        }

        /**
         * Sets the embedding store (vector database).
         *
         * @param embeddingStore the vector store to use
         * @return this builder for method chaining
         */
        public Builder embeddingStore(VDBStoreBase embeddingStore) {
            this.embeddingStore = embeddingStore;
            return this;
        }

        /**
         * Builds a new SimpleKnowledge instance.
         *
         * @return a new SimpleKnowledge instance
         * @throws IllegalArgumentException if required parameters are missing
         */
        public SimpleKnowledge build() {
            if (embeddingModel == null) {
                throw new IllegalArgumentException("Embedding model is required");
            }
            if (embeddingStore == null) {
                throw new IllegalArgumentException("Embedding store is required");
            }
            return new SimpleKnowledge(embeddingModel, embeddingStore);
        }
    }
}
