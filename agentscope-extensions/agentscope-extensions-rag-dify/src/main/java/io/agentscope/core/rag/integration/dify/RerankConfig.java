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
 * Rerank configuration for Dify knowledge base retrieval.
 *
 * <p>Reranking improves retrieval quality by re-scoring documents using a specialized
 * ranking model. This is typically applied after initial retrieval to refine results.
 *
 * <p>Example usage:
 * <pre>{@code
 * RerankConfig rerankConfig = RerankConfig.builder()
 *     .providerName("cohere")           // Optional: reranking provider
 *     .modelName("rerank-english-v2.0") // Optional: model name
 *     .topN(5)                          // Optional: top N after reranking
 *     .build();
 *
 * DifyRAGConfig config = DifyRAGConfig.builder()
 *     .enableRerank(true)
 *     .rerankConfig(rerankConfig)
 *     .build();
 * }</pre>
 */
public class RerankConfig {

    private final String providerName;
    private final String modelName;

    private RerankConfig(Builder builder) {
        this.providerName = builder.providerName;
        this.modelName = builder.modelName;
    }

    /**
     * Gets the rerank provider name.
     *
     * @return the provider name (e.g., "cohere", "jina"), or null if using default
     */
    public String getProviderName() {
        return providerName;
    }

    /**
     * Gets the rerank model name.
     *
     * @return the model name, or null if using default
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * Creates a new builder for RerankConfig.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for RerankConfig.
     */
    public static class Builder {
        private String providerName;
        private String modelName;

        private Builder() {}

        /**
         * Sets the rerank provider name.
         *
         * <p>This specifies which reranking service provider to use.
         *
         * <p>Common providers:
         * <ul>
         *   <li>cohere
         *   <li>jina
         *   <li>local (for self-hosted models)
         * </ul>
         *
         * @param providerName the provider name
         * @return this builder for method chaining
         */
        public Builder providerName(String providerName) {
            this.providerName = providerName;
            return this;
        }

        /**
         * Sets the rerank model name.
         *
         * <p>This is the name of the reranking model provided by the specified provider.
         * If not set, the provider's default reranking model will be used.
         *
         * <p>Common models:
         * <ul>
         *   <li>rerank-english-v2.0 (Cohere)
         *   <li>rerank-multilingual-v2.0 (Cohere)
         *   <li>jina-reranker-v1-base-en (Jina)
         *   <li>bge-reranker-base (local)
         *   <li>bge-reranker-large (local)
         * </ul>
         *
         * @param modelName the model name
         * @return this builder for method chaining
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * Builds a new RerankConfig instance.
         *
         * @return a new RerankConfig instance
         */
        public RerankConfig build() {
            return new RerankConfig(this);
        }
    }

    @Override
    public String toString() {
        return "RerankConfig{"
                + "providerName='"
                + providerName
                + '\''
                + ", modelName='"
                + modelName
                + '}';
    }
}
