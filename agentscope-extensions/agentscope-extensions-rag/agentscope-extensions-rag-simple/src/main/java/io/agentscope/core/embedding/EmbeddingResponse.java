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

/**
 * Represents an embedding response from an embedding model.
 *
 * <p>This immutable data class contains the embedding vector and optional usage information
 * returned by the model after processing an embedding request.
 */
public class EmbeddingResponse {

    private final double[] embedding;
    private final EmbeddingUsage usage;

    /**
     * Creates a new EmbeddingResponse instance.
     *
     * @param embedding the embedding vector
     * @param usage the token usage information for this response; may be null when the
     *     provider does not report usage
     */
    public EmbeddingResponse(double[] embedding, EmbeddingUsage usage) {
        this.embedding = embedding;
        this.usage = usage;
    }

    /**
     * Gets the embedding vector.
     *
     * @return the embedding vector
     */
    public double[] getEmbedding() {
        return embedding;
    }

    /**
     * Gets the token usage information for this response.
     *
     * @return the usage information, or null when the provider does not report usage
     */
    public EmbeddingUsage getUsage() {
        return usage;
    }

    /**
     * Creates a new builder for EmbeddingResponse.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating EmbeddingResponse instances.
     */
    public static class Builder {
        private double[] embedding;
        private EmbeddingUsage usage;

        /**
         * Sets the embedding vector.
         *
         * @param embedding the embedding vector
         * @return this builder instance
         */
        public Builder embedding(double[] embedding) {
            this.embedding = embedding;
            return this;
        }

        /**
         * Sets the usage information.
         *
         * @param usage the token usage information
         * @return this builder instance
         */
        public Builder usage(EmbeddingUsage usage) {
            this.usage = usage;
            return this;
        }

        /**
         * Builds a new EmbeddingResponse instance with the set values.
         *
         * @return a new EmbeddingResponse instance
         */
        public EmbeddingResponse build() {
            return new EmbeddingResponse(embedding, usage);
        }
    }
}
