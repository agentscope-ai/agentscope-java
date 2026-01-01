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
package io.agentscope.core.rag.store;

import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.model.Document;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;

/**
 * In-memory implementation of vector database storage.
 *
 * <p>This implementation stores vectors in memory using a ConcurrentHashMap for thread safety.
 * It uses cosine similarity for vector search, which is the most common metric for embedding
 * vectors.
 *
 * <p>This implementation is suitable for:
 * <ul>
 *   <li>Development and testing
 *   <li>Small to medium-sized datasets
 *   <li>Prototyping RAG systems
 * </ul>
 *
 * <p>For production use with large datasets, consider using a dedicated vector database
 * like Qdrant, ChromaDB, or Pinecone.
 *
 * <p>Example usage:
 * <pre>{@code
 * InMemoryStore store = InMemoryStore.builder()
 *     .dimensions(1024)
 *     .build();
 *
 * // Add documents
 * store.add(documents).block();
 *
 * // Search
 * List<Document> results = store.search(queryEmbedding, 5, 0.8).block();
 * }</pre>
 *
 * <p><b>Exception Handling:</b>
 * <ul>
 *   <li>{@link IllegalArgumentException} - for invalid input parameters (null documents, null embeddings, invalid limit)
 *   <li>{@link VectorStoreException} - for vector-specific errors (dimension mismatch)
 * </ul>
 */
public class InMemoryStore implements VDBStoreBase {

    private final Map<String, Document> documents;
    private final int dimensions;

    /**
     * Creates a new InMemoryStore with the specified vector dimensions.
     *
     * @param dimensions the dimension of vectors that will be stored
     * @throws IllegalArgumentException if dimensions is not positive
     */
    private InMemoryStore(final int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive");
        }
        this.dimensions = dimensions;
        this.documents = new ConcurrentHashMap<>();
    }

    @Override
    public Mono<Void> add(final List<Document> documentList) {
        if (documentList == null) {
            return Mono.error(new IllegalArgumentException("Document list cannot be null"));
        }
        if (documentList.isEmpty()) {
            return Mono.empty();
        }

        return Mono.fromCallable(
                () -> {
                    for (Document document : documentList) {
                        if (document == null) {
                            throw new IllegalArgumentException("Document cannot be null");
                        }
                        if (document.getEmbedding() == null) {
                            throw new IllegalArgumentException("Document must have embedding set");
                        }

                        validateDimensions(document.getEmbedding(), "Embedding");

                        // Create a defensive copy of the embedding
                        double[] embeddingCopy =
                                Arrays.copyOf(
                                        document.getEmbedding(), document.getEmbedding().length);
                        Document docCopy = new Document(document.getMetadata());
                        docCopy.setEmbedding(embeddingCopy);
                        documents.put(document.getId(), docCopy);
                    }
                    return null;
                });
    }

    @Override
    public Mono<List<Document>> search(
            final double[] queryEmbedding, final int limit, final Double scoreThreshold) {
        try {
            validateDimensions(queryEmbedding, "Query embedding");
        } catch (Exception e) {
            return Mono.error(e);
        }

        if (limit <= 0) {
            return Mono.error(new IllegalArgumentException("Limit must be positive"));
        }

        return Mono.fromCallable(
                () -> {
                    if (documents.isEmpty()) {
                        return new ArrayList<>();
                    }

                    List<Document> results = new ArrayList<>();

                    // Calculate similarity for all documents
                    for (Document doc : documents.values()) {
                        double similarity =
                                DistanceCalculator.cosineSimilarity(
                                        queryEmbedding, doc.getEmbedding());

                        // Apply score threshold if specified
                        if (scoreThreshold != null && similarity < scoreThreshold) {
                            continue;
                        }

                        Document docWithScore = new Document(doc.getMetadata());
                        docWithScore.setEmbedding(doc.getEmbedding());
                        docWithScore.setScore(similarity);
                        results.add(docWithScore);
                    }

                    // Sort by similarity (descending) and take top results
                    return results.stream()
                            .sorted(
                                    Comparator.comparing(
                                            Document::getScore,
                                            Comparator.nullsLast(Comparator.reverseOrder())))
                            .limit(limit)
                            .toList();
                });
    }

    @Override
    public Mono<Boolean> delete(final String id) {
        if (id == null) {
            return Mono.error(new IllegalArgumentException("Document ID cannot be null"));
        }
        return Mono.fromCallable(
                () -> {
                    Document removed = documents.remove(id);
                    return removed != null;
                });
    }

    /**
     * Validates that an embedding has the correct dimensions.
     *
     * @param embedding the embedding to validate
     * @param paramName the parameter name for error messages
     * @throws IllegalArgumentException if embedding is null
     * @throws VectorStoreException if embedding dimension does not match expected dimensions
     */
    private void validateDimensions(final double[] embedding, final String paramName)
            throws VectorStoreException {
        if (embedding == null) {
            throw new IllegalArgumentException(paramName + " cannot be null");
        }
        if (embedding.length != dimensions) {
            throw new VectorStoreException(
                    String.format(
                            "%s dimension mismatch: expected %d, got %d",
                            paramName, dimensions, embedding.length));
        }
    }

    /**
     * Gets the number of documents currently stored.
     *
     * <p>This method is thread-safe and returns the current snapshot of the store size.
     *
     * @return the number of stored documents (always non-negative)
     */
    public int size() {
        return documents.size();
    }

    /**
     * Checks if the store is empty.
     *
     * <p>Equivalent to {@code size() == 0}. This method is thread-safe.
     *
     * @return true if the store contains no documents, false otherwise
     */
    public boolean isEmpty() {
        return documents.isEmpty();
    }

    /**
     * Clears all documents from the store.
     *
     * <p>After this operation, {@link #size()} returns 0 and {@link #isEmpty()} returns true.
     * This operation is thread-safe.
     */
    public void clear() {
        documents.clear();
    }

    /**
     * Gets the dimension of vectors stored in this store.
     *
     * @return the vector dimension
     */
    public int getDimensions() {
        return dimensions;
    }

    /**
     * Creates a new builder for InMemoryStore.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for InMemoryStore.
     */
    public static class Builder {
        private int dimensions = 1024; // Default dimensions

        private Builder() {}

        /**
         * Sets the vector dimensions.
         *
         * @param dimensions the dimension of vectors to be stored (must be positive)
         * @return this builder for method chaining
         */
        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        /**
         * Builds a new InMemoryStore instance.
         *
         * @return a new InMemoryStore instance
         * @throws IllegalArgumentException if dimensions is not positive
         */
        public InMemoryStore build() {
            return new InMemoryStore(dimensions);
        }
    }
}
