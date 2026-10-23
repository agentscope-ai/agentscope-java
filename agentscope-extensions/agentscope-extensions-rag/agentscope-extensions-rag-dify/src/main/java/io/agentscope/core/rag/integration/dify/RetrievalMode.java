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

/**
 * Dify retrieval modes for knowledge base search.
 *
 * <p>Different retrieval modes use different strategies to find relevant documents:
 * <ul>
 *   <li><b>KEYWORD_SEARCH</b>: Traditional keyword-based search (BM25, TF-IDF)
 *   <li><b>SEMANTIC_SEARCH</b>: Vector-based semantic similarity search
 *   <li><b>HYBRID_SEARCH</b>: Combines keyword and semantic search (recommended)
 *   <li><b>FULL_TEXT_SEARCH</b>: Full-text search across all document content
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * DifyRAGConfig config = DifyRAGConfig.builder()
 *     .retrievalMode(RetrievalMode.HYBRID_SEARCH)  // Use hybrid search
 *     .build();
 * }</pre>
 */
public enum RetrievalMode {

    /**
     * Keyword-based retrieval using BM25 or similar algorithms.
     *
     * <p>Best for: Exact term matching, proper nouns, technical terms
     */
    KEYWORD_SEARCH("keyword_search"),

    /**
     * Semantic retrieval using vector embeddings and similarity search.
     *
     * <p>Best for: Conceptual queries, paraphrased questions, semantic understanding
     */
    SEMANTIC_SEARCH("semantic_search"),

    /**
     * Hybrid retrieval combining keyword and semantic approaches.
     *
     * <p>This is the recommended mode as it provides the best of both worlds.
     * Results from both methods are merged and reranked for optimal relevance.
     *
     * <p>Best for: General use, balanced precision and recall
     */
    HYBRID_SEARCH("hybrid_search"),

    /**
     * Full-text search across all document content.
     *
     * <p>Best for: Complex queries, searching within large text bodies
     */
    FULL_TEXT_SEARCH("full_text_search");

    private final String value;

    /**
     * Creates a new RetrievalMode enum constant.
     *
     * @param value the string value used in Dify API
     */
    RetrievalMode(String value) {
        this.value = value;
    }

    /**
     * Gets the string value for use in Dify API requests.
     *
     * @return the API value
     */
    public String getValue() {
        return value;
    }

    /**
     * Converts a string value to the corresponding RetrievalMode enum.
     *
     * @param value the string value
     * @return the matching RetrievalMode
     * @throws IllegalArgumentException if the value doesn't match any mode
     */
    public static RetrievalMode fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Retrieval mode value cannot be null");
        }

        for (RetrievalMode mode : values()) {
            if (mode.value.equalsIgnoreCase(value)) {
                return mode;
            }
        }

        throw new IllegalArgumentException(
                "Unknown retrieval mode: "
                        + value
                        + ". Valid values are: keyword_search, semantic_search, hybrid_search,"
                        + " full_text_search");
    }

    @Override
    public String toString() {
        return value;
    }
}
