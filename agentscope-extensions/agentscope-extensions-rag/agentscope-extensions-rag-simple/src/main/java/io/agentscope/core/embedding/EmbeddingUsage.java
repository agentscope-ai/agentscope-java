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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents token usage information for embedding responses.
 *
 * <p>This immutable data class tracks the number of tokens used during an embedding request.
 * Unlike chat completions, embedding requests only consume input tokens; there is no generated
 * output to bill.
 */
public class EmbeddingUsage {

    private final int inputTokens;
    private final int totalTokens;
    private final double time;

    /**
     * Creates a new EmbeddingUsage instance.
     *
     * @param inputTokens the number of tokens used for the input
     * @param totalTokens the total number of tokens reported by the provider
     * @param time the end-to-end execution time in seconds, measured on the client side
     */
    @JsonCreator
    public EmbeddingUsage(
            @JsonProperty("inputTokens") int inputTokens,
            @JsonProperty("totalTokens") int totalTokens,
            @JsonProperty("time") double time) {
        this.inputTokens = inputTokens;
        this.totalTokens = totalTokens;
        this.time = time;
    }

    /**
     * Gets the number of input tokens used.
     *
     * @return the number of tokens used for the input
     */
    public int getInputTokens() {
        return inputTokens;
    }

    /**
     * Gets the total number of tokens reported by the provider.
     *
     * <p>For embedding requests this typically equals {@link #getInputTokens()}, since
     * embeddings consume only input tokens. Providers that do not distinguish the two report
     * the same value in both fields.
     *
     * @return the total number of tokens
     */
    public int getTotalTokens() {
        return totalTokens;
    }

    /**
     * Gets the end-to-end execution time measured on the client side.
     *
     * <p>This is the elapsed time from when the embedding call was initiated to when
     * the response (including vector parsing) was completed. It is not the server-side
     * processing time reported by the provider.
     *
     * @return the execution time in seconds
     */
    public double getTime() {
        return time;
    }

    /**
     * Creates a new builder for EmbeddingUsage.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating EmbeddingUsage instances.
     */
    public static class Builder {
        private int inputTokens;
        private int totalTokens;
        private double time;

        /**
         * Sets the number of input tokens.
         *
         * @param inputTokens the number of tokens used for the input
         * @return this builder instance
         */
        public Builder inputTokens(int inputTokens) {
            this.inputTokens = inputTokens;
            return this;
        }

        /**
         * Sets the total number of tokens.
         *
         * @param totalTokens the total number of tokens
         * @return this builder instance
         */
        public Builder totalTokens(int totalTokens) {
            this.totalTokens = totalTokens;
            return this;
        }

        /**
         * Sets the end-to-end execution time measured on the client side.
         *
         * @param time the end-to-end execution time in seconds, measured on the client side
         * @return this builder instance
         */
        public Builder time(double time) {
            this.time = time;
            return this;
        }

        /**
         * Builds a new EmbeddingUsage instance with the set values.
         *
         * @return a new EmbeddingUsage instance
         */
        public EmbeddingUsage build() {
            return new EmbeddingUsage(inputTokens, totalTokens, time);
        }
    }
}
