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
package io.agentscope.core.embedding.dashscope;

import com.alibaba.dashscope.embeddings.MultiModalEmbedding;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingItemImage;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingItemText;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingParam;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingResult;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingResultItem;
import io.agentscope.core.embedding.EmbeddingException;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.EmbeddingUtils;
import io.agentscope.core.formatter.dashscope.DashScopeMediaConverter;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ExecutionConfig;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * DashScope Multi-Modal Embedding Model implementation.
 *
 * <p>This implementation provides access to DashScope's multi-modal embedding API, supporting
 * both text and image embedding operations via ContentBlock input. All content types are processed
 * through the MultiModalEmbedding API for consistency.
 *
 * <p>Supports:
 * <ul>
 *   <li>{@link TextBlock} - via DashScope MultiModalEmbedding API with MultiModalEmbeddingItemText
 *   <li>{@link ImageBlock} - via DashScope MultiModalEmbedding API with MultiModalEmbeddingItemImage
 * </ul>
 *
 * <p>Other ContentBlock types are not supported and will result in an {@link EmbeddingException}.
 *
 * <p>Supports timeout and retry configuration through ExecutionConfig.
 */
public class DashScopeMultiModalEmbedding implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(DashScopeMultiModalEmbedding.class);

    private final String apiKey;
    private final String modelName;
    private final int dimensions;
    private final ExecutionConfig defaultExecutionConfig;
    private final DashScopeMediaConverter mediaConverter;

    private final String baseUrl;

    /**
     * Creates a new DashScope multi-modal embedding model instance.
     *
     * @param apiKey the API key for DashScope authentication
     * @param modelName the model name (e.g., "multimodal-embedding-v1")
     * @param dimensions the dimension of embedding vectors
     * @param defaultExecutionConfig default execution configuration for timeout and retry
     * @param baseUrl custom base URL for DashScope API (null for default)
     */
    public DashScopeMultiModalEmbedding(
            String apiKey,
            String modelName,
            int dimensions,
            ExecutionConfig defaultExecutionConfig,
            String baseUrl) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.dimensions = dimensions;
        this.defaultExecutionConfig =
                EmbeddingUtils.ensureDefaultExecutionConfig(defaultExecutionConfig);
        this.baseUrl = baseUrl;
        this.mediaConverter = new DashScopeMediaConverter();
    }

    /**
     * Creates a new builder for DashScopeMultiModalEmbedding.
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
                    new EmbeddingException("ContentBlock cannot be null", modelName, "dashscope"));
        }

        Mono<double[]> embeddingMono =
                Mono.fromCallable(
                                () -> {
                                    try {
                                        return embed0(block);
                                    } catch (EmbeddingException e) {
                                        throw e;
                                    } catch (Exception e) {
                                        throw new EmbeddingException(
                                                "Failed to generate embedding: " + e.getMessage(),
                                                e,
                                                modelName,
                                                "dashscope");
                                    }
                                })
                        .onErrorMap(
                                e -> {
                                    if (e instanceof EmbeddingException) {
                                        return e;
                                    }
                                    return new EmbeddingException(
                                            "DashScope multi-modal embedding API call failed: "
                                                    + e.getMessage(),
                                            e,
                                            modelName,
                                            "dashscope");
                                });

        // Apply timeout and retry
        return EmbeddingUtils.applyTimeoutAndRetry(
                embeddingMono, defaultExecutionConfig, modelName, "dashscope", log);
    }

    /**
     * Embeds a single ContentBlock (TextBlock or ImageBlock) using MultiModalEmbedding API.
     *
     * @param block the content block to embed
     * @return embedding vector
     * @throws EmbeddingException if embedding fails or block type is unsupported
     */
    private double[] embed0(ContentBlock block) throws EmbeddingException {
        try {
            MultiModalEmbeddingParam param;

            if (block instanceof TextBlock textBlock) {
                String text = textBlock.getText();
                if (text == null || text.trim().isEmpty()) {
                    throw new EmbeddingException(
                            "TextBlock text cannot be null or empty", modelName, "dashscope");
                }
                param = buildTextParam(text);
            } else if (block instanceof ImageBlock imageBlock) {
                String imageUrl = mediaConverter.convertImageBlockToUrl(imageBlock);
                param = buildImageParam(imageUrl);
            } else {
                throw new EmbeddingException(
                        "DashScopeMultiModalEmbedding only supports"
                                + " TextBlock and ImageBlock, but got: "
                                + block.getClass().getSimpleName(),
                        modelName,
                        "dashscope");
            }

            MultiModalEmbeddingResult result = callEmbeddingAPI(param);
            double[] embedding = parseEmbeddingResult(result);
            validateDimensions(embedding);

            return embedding;
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException(
                    "Failed to embed content block: " + e.getMessage(), e, modelName, "dashscope");
        }
    }

    /**
     * Creates a new MultiModalEmbedding client instance.
     *
     * <p>Note: A new client instance is created for each request because the DashScope SDK
     * does not currently support client reuse for multi-modal embeddings. Each client is
     * lightweight and designed for single-use operations.
     *
     * @return a new MultiModalEmbedding instance
     */
    private MultiModalEmbedding createEmbeddingClient() {
        return baseUrl != null ? new MultiModalEmbedding(baseUrl) : new MultiModalEmbedding();
    }

    /**
     * Builds a MultiModalEmbeddingParam for text embedding.
     *
     * @param text the text to embed
     * @return configured MultiModalEmbeddingParam
     */
    private MultiModalEmbeddingParam buildTextParam(String text) {
        return MultiModalEmbeddingParam.builder()
                .apiKey(apiKey)
                .model(modelName)
                .parameters(Map.of("dimension", dimensions))
                .contents(List.of(MultiModalEmbeddingItemText.builder().text(text).build()))
                .build();
    }

    /**
     * Builds a MultiModalEmbeddingParam for image embedding.
     *
     * @param imageUrl the image URL to embed
     * @return configured MultiModalEmbeddingParam
     */
    private MultiModalEmbeddingParam buildImageParam(String imageUrl) {
        return MultiModalEmbeddingParam.builder()
                .apiKey(apiKey)
                .model(modelName)
                .parameters(Map.of("dimension", dimensions))
                .contents(List.of(MultiModalEmbeddingItemImage.builder().image(imageUrl).build()))
                .build();
    }

    /**
     * Calls the DashScope MultiModalEmbedding API with the given parameter.
     *
     * @param param the embedding parameter
     * @return the embedding result
     * @throws EmbeddingException if the API call fails or returns empty response
     */
    private MultiModalEmbeddingResult callEmbeddingAPI(MultiModalEmbeddingParam param)
            throws EmbeddingException {
        try {
            MultiModalEmbedding embedding = createEmbeddingClient();
            MultiModalEmbeddingResult result = embedding.call(param);

            if (result == null || result.getOutput() == null) {
                throw new EmbeddingException(
                        "Empty response from DashScope multi-modal embedding API",
                        modelName,
                        "dashscope");
            }

            return result;
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException(
                    "DashScope multi-modal embedding API call failed: " + e.getMessage(),
                    e,
                    modelName,
                    "dashscope");
        }
    }

    /**
     * Parses the embedding result and converts it to a double array.
     *
     * @param result the embedding result from API
     * @return the embedding vector as double array
     * @throws EmbeddingException if the result is invalid or empty
     */
    private double[] parseEmbeddingResult(MultiModalEmbeddingResult result)
            throws EmbeddingException {
        List<MultiModalEmbeddingResultItem> embeddings = result.getOutput().getEmbeddings();
        if (embeddings == null || embeddings.isEmpty() || embeddings.get(0) == null) {
            throw new EmbeddingException("No embedding data in response", modelName, "dashscope");
        }

        List<Double> embeddingValues = embeddings.get(0).getEmbedding();
        if (embeddingValues == null || embeddingValues.isEmpty()) {
            throw new EmbeddingException(
                    "Empty embedding vector in response", modelName, "dashscope");
        }

        return EmbeddingUtils.convertDoubleListToArray(embeddingValues);
    }

    /**
     * Validates the embedding dimension and logs a warning if mismatch.
     *
     * @param embedding the embedding vector to validate
     */
    private void validateDimensions(double[] embedding) {
        if (embedding.length != dimensions) {
            log.warn(
                    "Embedding dimension mismatch: expected={}, actual={}",
                    dimensions,
                    embedding.length);
        }
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
     * Builder for DashScopeMultiModalEmbedding.
     */
    public static class Builder {
        private String apiKey;
        private String modelName;
        private int dimensions = 1024;
        private ExecutionConfig defaultExecutionConfig;
        private String baseUrl;

        /**
         * Sets the API key for DashScope authentication.
         *
         * @param apiKey the API key
         * @return this builder instance
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the model name to use.
         *
         * @param modelName the model name (e.g., "multimodal-embedding-v1")
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
         * Sets a custom base URL for DashScope API.
         *
         * @param baseUrl the base URL (null for default)
         * @return this builder instance
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Builds the DashScopeMultiModalEmbedding instance.
         *
         * <p>This method validates required parameters and ensures that the defaultExecutionConfig
         * always has proper defaults applied using EmbeddingUtils.ensureDefaultExecutionConfig().
         *
         * @return configured DashScopeMultiModalEmbedding instance
         * @throws IllegalStateException if required parameters are missing or invalid
         */
        public DashScopeMultiModalEmbedding build() {
            // Validate required parameters
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalStateException("apiKey is required and cannot be null or empty");
            }
            if (modelName == null || modelName.isEmpty()) {
                throw new IllegalStateException(
                        "modelName is required and cannot be null or empty");
            }
            if (dimensions <= 0) {
                throw new IllegalStateException("dimensions must be positive, got: " + dimensions);
            }

            ExecutionConfig effectiveConfig =
                    EmbeddingUtils.ensureDefaultExecutionConfig(defaultExecutionConfig);

            return new DashScopeMultiModalEmbedding(
                    apiKey, modelName, dimensions, effectiveConfig, baseUrl);
        }
    }
}
