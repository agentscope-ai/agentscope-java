/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.embedding.ollama;

import io.agentscope.core.embedding.EmbeddingException;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.EmbeddingUtils;
import io.agentscope.core.formatter.ollama.dto.OllamaEmbeddingRequest;
import io.agentscope.core.formatter.ollama.dto.OllamaEmbeddingResponse;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.OllamaHttpClient;
import io.agentscope.core.model.transport.HttpTransportFactory;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Ollama Text Embedding Model implementation.
 *
 * <p>This implementation provides access to Ollama's text embedding API, supporting both
 * single text embedding and batch embedding operations.
 *
 * <p>Supports only {@link TextBlock} content blocks. Other content block types will result in
 * an {@link EmbeddingException}.
 *
 * <p>Supports timeout and retry configuration through ExecutionConfig.
 *
 */
public class OllamaTextEmbedding implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(OllamaTextEmbedding.class);

    private final String baseUrl;
    private final String modelName;
    private final int dimensions;
    private final ExecutionConfig defaultExecutionConfig;

    /**
     * Creates a new Ollama text embedding model instance.
     *
     * @param baseUrl the base URL for Ollama API (e.g., "http://localhost:11434")
     * @param modelName the model name (e.g., "nomic-embed-text", "all-minilm")
     * @param dimensions the dimension of embedding vectors
     * @param defaultExecutionConfig default execution configuration for timeout and retry
     */
    public OllamaTextEmbedding(
            String baseUrl,
            String modelName,
            int dimensions,
            ExecutionConfig defaultExecutionConfig) {
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.dimensions = dimensions;
        this.defaultExecutionConfig =
                EmbeddingUtils.ensureDefaultExecutionConfig(defaultExecutionConfig);
    }

    /**
     * Creates a new builder for OllamaTextEmbedding.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Mono<double[]> embed(ContentBlock block) {
        if (block == null) {
            return Mono.error(
                    new EmbeddingException("ContentBlock cannot be null", modelName, "ollama"));
        }

        if (!(block instanceof TextBlock textBlock)) {
            return Mono.error(
                    new EmbeddingException(
                            "OllamaTextEmbedding only supports TextBlock, but got: "
                                    + block.getClass().getSimpleName(),
                            modelName,
                            "ollama"));
        }

        String text = textBlock.getText();
        if (text == null || text.trim().isEmpty()) {
            return Mono.error(
                    new EmbeddingException(
                            "TextBlock text cannot be null or empty", modelName, "ollama"));
        }

        Mono<double[]> embeddingMono =
                Mono.fromCallable(
                                () -> {
                                    try {
                                        log.debug(
                                                "Ollama embedding call: model={},"
                                                        + " text_length={}",
                                                modelName,
                                                text.length());

                                        // Create Ollama HTTP client
                                        OllamaHttpClient client =
                                                new OllamaHttpClient(
                                                        HttpTransportFactory.getDefault(), baseUrl);

                                        // Create embedding request
                                        OllamaEmbeddingRequest request =
                                                new OllamaEmbeddingRequest(
                                                        modelName, List.of(text));

                                        // Set additional parameters from options
                                        request.setKeepAlive(null); // Use default keep alive
                                        request.setTruncate(null); // Use default truncation

                                        // Call the Ollama embedding API
                                        OllamaEmbeddingResponse response = client.embed(request);

                                        if (response == null || response.getEmbeddings() == null) {
                                            throw new EmbeddingException(
                                                    "Empty response from Ollama embedding API",
                                                    modelName,
                                                    "ollama");
                                        }

                                        List<float[]> embeddings = response.getEmbeddings();
                                        if (embeddings.isEmpty() || embeddings.get(0) == null) {
                                            throw new EmbeddingException(
                                                    "No embedding data in response",
                                                    modelName,
                                                    "ollama");
                                        }

                                        // Convert float[] to double[]
                                        float[] embeddingValues = embeddings.get(0);
                                        double[] embeddingArray =
                                                EmbeddingUtils.convertFloatArrayToDoubleArray(
                                                        embeddingValues);

                                        // Validate dimension if specified
                                        if (dimensions > 0 && embeddingArray.length != dimensions) {
                                            log.warn(
                                                    "Embedding dimension mismatch: expected={},"
                                                            + " actual={}",
                                                    dimensions,
                                                    embeddingArray.length);
                                        }

                                        return embeddingArray;
                                    } catch (EmbeddingException e) {
                                        throw e;
                                    } catch (Exception e) {
                                        // Handle specific Ollama HTTP exception
                                        if (e instanceof OllamaHttpClient.OllamaHttpException) {
                                            OllamaHttpClient.OllamaHttpException ohe =
                                                    (OllamaHttpClient.OllamaHttpException) e;
                                            throw new EmbeddingException(
                                                    "Ollama API error: " + ohe.getMessage(),
                                                    e,
                                                    modelName,
                                                    "ollama");
                                        }
                                        throw new EmbeddingException(
                                                "Failed to generate embedding: " + e.getMessage(),
                                                e,
                                                modelName,
                                                "ollama");
                                    }
                                })
                        .onErrorMap(
                                e -> {
                                    if (e instanceof EmbeddingException) {
                                        return e;
                                    }
                                    return new EmbeddingException(
                                            "Ollama embedding API call failed: " + e.getMessage(),
                                            e,
                                            modelName,
                                            "ollama");
                                });

        // Apply timeout and retry
        return EmbeddingUtils.applyTimeoutAndRetry(
                embeddingMono, defaultExecutionConfig, modelName, "ollama", log);
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public int getDimensions() {
        return dimensions;
    }

    /**
     * Builder for OllamaTextEmbedding.
     */
    public static class Builder {
        private String baseUrl = "http://localhost:11434"; // Default Ollama endpoint
        private String modelName;
        private int dimensions = -1; // Default to -1 to indicate unspecified
        private ExecutionConfig defaultExecutionConfig;

        /**
         * Sets the base URL for Ollama API.
         *
         * @param baseUrl the base URL (e.g., "http://localhost:11434")
         * @return this builder instance
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the model name to use.
         *
         * @param modelName the model name (e.g., "nomic-embed-text", "all-minilm")
         * @return this builder instance
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * Sets the dimension of embedding vectors.
         *
         * @param dimensions the dimension
         * @return this builder instance
         */
        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        /**
         * Sets the default execution configuration.
         *
         * @param config the execution config (null for defaults)
         * @return this builder instance
         */
        public Builder executionConfig(ExecutionConfig config) {
            this.defaultExecutionConfig = config;
            return this;
        }

        /**
         * Builds the OllamaTextEmbedding instance.
         *
         * <p>This method validates required parameters and ensures that the defaultExecutionConfig
         * always has proper defaults applied using EmbeddingUtils.ensureDefaultExecutionConfig().
         *
         * @return configured OllamaTextEmbedding instance
         * @throws IllegalStateException if required parameters are missing or invalid
         */
        public OllamaTextEmbedding build() {
            // Validate required parameters
            if (modelName == null || modelName.isEmpty()) {
                throw new IllegalStateException(
                        "modelName is required and cannot be null or empty");
            }
            if (dimensions <= 0 && dimensions != -1) {
                throw new IllegalStateException("dimensions must be positive, got: " + dimensions);
            }

            ExecutionConfig effectiveConfig =
                    EmbeddingUtils.ensureDefaultExecutionConfig(defaultExecutionConfig);

            return new OllamaTextEmbedding(baseUrl, modelName, dimensions, effectiveConfig);
        }
    }
}
