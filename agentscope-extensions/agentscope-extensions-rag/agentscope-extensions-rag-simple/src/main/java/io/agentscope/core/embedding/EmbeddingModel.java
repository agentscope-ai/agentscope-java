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
package io.agentscope.core.embedding;

import io.agentscope.core.message.ContentBlock;
import reactor.core.publisher.Mono;

/**
 * Interface for embedding models supporting multimodal content.
 *
 * <p>This interface provides a unified API for generating vector embeddings from content blocks.
 *
 * <p>Each implementation determines which content block types it supports:
 * <ul>
 *   <li>Text-only models (e.g., DashScopeTextEmbedding) support {@link io.agentscope.core.message.TextBlock}
 *   <li>Multimodal models (e.g., DashScopeMultiModalEmbedding) support {@link io.agentscope.core.message.TextBlock}
 *       and {@link io.agentscope.core.message.ImageBlock}
 * </ul>
 *
 * <p>Embedding models are used in RAG (Retrieval-Augmented Generation) systems to convert content
 * into vector representations for semantic search and similarity matching.
 */
public interface EmbeddingModel {

    /**
     * Generate embedding vector for a single content block.
     *
     * <p>Implementations should check the content block type and throw {@link EmbeddingException}
     * if the type is not supported.
     *
     * @param block the content block to embed (e.g., TextBlock, ImageBlock)
     * @return Mono that emits a double array representing the embedding vector
     * @throws EmbeddingException if embedding generation fails or content block type is not
     *     supported
     */
    Mono<double[]> embed(ContentBlock block);

    /**
     * Get the model name for logging and identification.
     *
     * @return the model name
     */
    String getModelName();

    /**
     * Get the dimension of embedding vectors produced by this model.
     * The default value is 1024 if not specified.
     *
     * @return the dimension of embedding vectors
     */
    int getDimensions();
}
