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

import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Interface for vector database storage.
 *
 * <p>This interface provides a unified API for storing and searching vector embeddings.
 * Implementations can use in-memory storage, Qdrant, ChromaDB, or other vector databases.
 *
 * <p>The store directly stores Document objects (including metadata/payload), not just vectors.
 * This allows retrieval of complete documents with their metadata.
 *
 * <p>Key features:
 * <ul>
 *   <li>Batch document addition for efficiency
 *   <li>Vector similarity search with optional score threshold
 *   <li>Document retrieval with embeddings and metadata
 * </ul>
 */
public interface VDBStoreBase {

    /**
     * Adds multiple documents to the store.
     *
     * <p>All documents must have embedding vectors set. The store will persist both
     * the vectors and the document metadata/payloads.
     *
     * <p>This method supports batch operations for efficiency. Implementations should
     * handle batch insertion optimally.
     *
     * @param documents the list of documents to store (all must have embeddings set)
     * @return a Mono that completes when all documents are stored
     */
    Mono<Void> add(List<Document> documents);

    /**
     * Searches for similar documents.
     *
     * <p>This method performs vector similarity search and returns the most similar documents.
     * Results include the document metadata and similarity scores. Optionally, embeddings
     * may also be included in the results (implementation-dependent).
     *
     * @param searchDocumentDto the search document DTO
     * @return a Mono that emits a list of Document objects with scores set, sorted by similarity
     *     (descending)
     */
    Mono<List<Document>> search(SearchDocumentDto searchDocumentDto);

    /**
     * Deletes a single stored chunk by its id.
     *
     * <p>{@code id} is the chunk's {@link Document#getId()}, the key the chunk was stored under
     * by {@link #add(List)}. Other chunks of the same source document are not affected; use
     * {@link #deleteByDocId(String)} to remove a whole document.
     *
     * @param id the chunk id ({@link Document#getId()}) to delete
     * @return a Mono that emits {@code true} if a chunk with this id existed and was removed, or
     *     {@code false} if the store held no such chunk
     */
    Mono<Boolean> delete(String id);

    /**
     * Deletes every chunk of one source document.
     *
     * <p>Removes all stored chunks whose {@link
     * io.agentscope.core.rag.model.DocumentMetadata#getDocId()} equals {@code docId}, i.e. all
     * chunks produced from the same source by a reader.
     *
     * <p>The default implementation emits {@link UnsupportedOperationException}; stores that
     * can delete by document should override it.
     *
     * @param docId the source document id ({@code DocumentMetadata#getDocId()})
     * @return a Mono that emits {@code true} if at least one chunk was removed, or {@code false}
     *     if the store held no chunk of this document
     */
    default Mono<Boolean> deleteByDocId(String docId) {
        return Mono.error(
                new UnsupportedOperationException(
                        getClass().getSimpleName() + " does not support deleteByDocId"));
    }
}
