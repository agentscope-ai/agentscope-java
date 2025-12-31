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
package io.agentscope.core.rag;

import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Interface for knowledge bases.
 *
 * <p>This interface provides a unified API for storing and retrieving documents
 * in a knowledge base. Knowledge bases are used in RAG (Retrieval-Augmented Generation)
 * systems to provide context to language models.
 */
public interface Knowledge {

    /**
     * Adds documents to the knowledge base.
     *
     * <p>Documents are embedded and stored in the vector database for later retrieval.
     *
     * @param documents the list of documents to add
     * @return a Mono that completes when all documents have been added
     */
    Mono<Void> addDocuments(List<Document> documents);

    /**
     * Retrieves relevant documents based on a query.
     *
     * <p>The query is embedded and used to search for similar documents in the
     * knowledge base. Results are filtered by the score threshold and limited
     * by the configured limit.
     *
     * @param query the search query text
     * @param config the retrieval configuration (limit, score threshold, etc.)
     * @return a Mono that emits a list of relevant Document objects, sorted by relevance
     */
    Mono<List<Document>> retrieve(String query, RetrieveConfig config);
}
