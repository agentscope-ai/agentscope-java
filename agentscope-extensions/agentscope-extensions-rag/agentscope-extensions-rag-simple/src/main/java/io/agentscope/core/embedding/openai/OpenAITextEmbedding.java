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
package io.agentscope.core.embedding.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import io.agentscope.core.Version;
import io.agentscope.core.embedding.EmbeddingException;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.EmbeddingUtils;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ExecutionConfig;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * OpenAI Text Embedding Model implementation.
 *
 * <p>This implementation provides access to OpenAI's text embedding API, supporting both
 * single text embedding and batch embedding operations.
 *
 * <p>Supports only {@link TextBlock} content blocks. Other content block types will result in
 * an {@link EmbeddingException}.
 *
 * <p>Supports timeout and retry configuration through ExecutionConfig.
 */
public class OpenAITextEmbedding implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAITextEmbedding.class);

    /** Default dimension when not explicitly configured, per EmbeddingModel interface contract. */
    private static final int DEFAULT_DIMENSIONS = 1024;

    private final String apiKey;
    private final String modelName;
    private final Integer dimensions;
    private final ExecutionConfig defaultExecutionConfig;

    private final String baseUrl;

    /**
     * Creates a new OpenAI text embedding model instance.
     *
     * <p>This constructor is kept for binary compatibility with existing compiled code.
     * It delegates to {@link #OpenAITextEmbedding(String, String, Integer, ExecutionConfig, String)}.
     *
     * @param apiKey the API key for OpenAI authentication
     * @param modelName the model name (e.g., "text-embedding-3-small")
     * @param dimensions the dimension of embedding vectors
     * @param defaultExecutionConfig default execution configuration for timeout and retry
     * @param baseUrl custom base URL for OpenAI API (null for default)
     */
    public OpenAITextEmbedding(
            String apiKey,
            String modelName,
            int dimensions,
            ExecutionConfig defaultExecutionConfig,
            String baseUrl) {
        this(apiKey, modelName, Integer.valueOf(dimensions), defaultExecutionConfig, baseUrl);
    }

    /**
     * Creates a new OpenAI text embedding model instance with optional dimensions.
     *
     * <p>When {@code dimensions} is null, the dimensions parameter will not be sent
     * to the API, allowing open-source models that don't support matryoshka
     * representation (e.g., BAAI/bge-large-zh-v1.5) to work correctly.
     *
     * @param apiKey the API key for OpenAI authentication
     * @param modelName the model name (e.g., "text-embedding-3-small")
     * @param dimensions the dimension of embedding vectors (null to omit from API request)
     * @param defaultExecutionConfig default execution configuration for timeout and retry
     * @param baseUrl custom base URL for OpenAI API (null for default)
     * @throws IllegalArgumentException if dimensions is non-null and not positive
     */
    public OpenAITextEmbedding(
            String apiKey,
            String modelName,
            Integer dimensions,
            ExecutionConfig defaultExecutionConfig,
            String baseUrl) {
        if (dimensions != null && dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive, got: " + dimensions);
        }
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.dimensions = dimensions;
        this.defaultExecutionConfig =
                EmbeddingUtils.ensureDefaultExecutionConfig(defaultExecutionConfig);
        this.baseUrl = baseUrl;
    }

    /**
     * Creates a new builder for OpenAITextEmbedding.
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
                    new EmbeddingException("ContentBlock cannot be null", modelName, "openai"));
        }

        if (!(block instanceof TextBlock textBlock)) {
            return Mono.error(
                    new EmbeddingException(
                            "OpenAITextEmbedding only supports TextBlock, but got: "
                                    + block.getClass().getSimpleName(),
                            modelName,
                            "openai"));
        }

        String text = textBlock.getText();
        if (text == null || text.trim().isEmpty()) {
            return Mono.error(
                    new EmbeddingException(
                            "TextBlock text cannot be null or empty", modelName, "openai"));
        }

        Mono<double[]> embeddingMono =
                Mono.fromCallable(
                                () -> {
                                    try {
                                        // Initialize OpenAI client
                                        OpenAIOkHttpClient.Builder clientBuilder =
                                                OpenAIOkHttpClient.builder();

                                        if (apiKey != null) {
                                            clientBuilder.apiKey(apiKey);
                                        }

                                        if (baseUrl != null) {
                                            clientBuilder.baseUrl(baseUrl);
                                        }

                                        // Set unified AgentScope User-Agent (overrides OpenAI SDK
                                        // default)
                                        clientBuilder.putHeader(
                                                "User-Agent", Version.getUserAgent());

                                        OpenAIClient client = clientBuilder.build();

                                        EmbeddingCreateParams.Builder paramsBuilder =
                                                EmbeddingCreateParams.builder()
                                                        .model(modelName)
                                                        .encodingFormat(
                                                                EmbeddingCreateParams.EncodingFormat
                                                                        .FLOAT)
                                                        .inputOfArrayOfStrings(List.of(text));

                                        if (dimensions != null) {
                                            paramsBuilder.dimensions(dimensions);
                                        }

                                        EmbeddingCreateParams createParams = paramsBuilder.build();

                                        log.debug(
                                                "OpenAI embedding call: model={},"
                                                        + " text_length={}",
                                                modelName,
                                                text.length());

                                        CreateEmbeddingResponse result =
                                                client.embeddings().create(createParams);

                                        if (result == null || result.data() == null) {
                                            throw new EmbeddingException(
                                                    "Empty response from OpenAI embedding API",
                                                    modelName,
                                                    "openai");
                                        }

                                        List<Embedding> embeddings = result.data();
                                        if (embeddings == null
                                                || embeddings.isEmpty()
                                                || embeddings.get(0) == null) {
                                            throw new EmbeddingException(
                                                    "No embedding data in response",
                                                    modelName,
                                                    "openai");
                                        }

                                        List<Float> embeddingValues = embeddings.get(0).embedding();
                                        if (embeddingValues == null || embeddingValues.isEmpty()) {
                                            throw new EmbeddingException(
                                                    "Empty embedding vector in response",
                                                    modelName,
                                                    "openai");
                                        }

                                        // Convert List<Float> to double[]
                                        double[] embeddingArray =
                                                EmbeddingUtils.convertFloatListToDoubleArray(
                                                        embeddingValues);

                                        // Validate dimension only when explicitly configured
                                        if (dimensions != null
                                                && embeddingArray.length != dimensions) {
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
                                        throw new EmbeddingException(
                                                "Failed to generate embedding: " + e.getMessage(),
                                                e,
                                                modelName,
                                                "openai");
                                    }
                                })
                        .onErrorMap(
                                e -> {
                                    if (e instanceof EmbeddingException) {
                                        return e;
                                    }
                                    return new EmbeddingException(
                                            "OpenAI embedding API call failed: " + e.getMessage(),
                                            e,
                                            modelName,
                                            "openai");
                                });

        // Apply timeout and retry
        return EmbeddingUtils.applyTimeoutAndRetry(
                embeddingMono, defaultExecutionConfig, modelName, "openai", log);
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the explicitly configured dimensions, or {@value #DEFAULT_DIMENSIONS} if {@code
     * dimensions} was never configured (per the {@link EmbeddingModel#getDimensions()} contract,
     * which documents 1024 as the default when unspecified). This fallback is a placeholder only:
     * for open-source models where dimensions is intentionally left unset, the true embedding
     * vector length returned by {@link #embed} may differ from this value. Callers needing the
     * exact size should inspect the length of the array returned by {@link #embed}, not this
     * getter.
     */
    @Override
    public int getDimensions() {
        return dimensions != null ? dimensions : DEFAULT_DIMENSIONS;
    }

    /**
     * Builder for OpenAITextEmbedding.
     */
    public static class Builder {
        private String apiKey;
        private String modelName;
        private Integer dimensions = 1536;
        private ExecutionConfig defaultExecutionConfig;
        private String baseUrl;

        /**
         * Sets the API key for OpenAI authentication.
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
         * @param modelName the model name (e.g., "text-embedding-3-small")
         * @return this builder instance
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * Sets the dimension of embedding vectors. Defaults to 1536.
         *
         * <p>When set to a positive value, the dimensions parameter will be included in the API
         * request. This is only supported by OpenAI official embedding models (e.g.,
         * text-embedding-3-small, text-embedding-3-large) that support matryoshka
         * representation.
         *
         * <p>For open-source models (e.g., BAAI/bge-large-zh-v1.5) that do not support this
         * parameter and will return a 400 error if it is sent, pass {@code null} to omit the
         * parameter from the request entirely.
         *
         * @param dimensions the dimension, or null to omit the parameter from the API request
         * @return this builder instance
         */
        public Builder dimensions(Integer dimensions) {
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
         * Sets a custom base URL for OpenAI API.
         *
         * @param baseUrl the base URL (null for default)
         * @return this builder instance
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Builds the OpenAITextEmbedding instance.
         *
         * <p>This method validates required parameters and ensures that the defaultExecutionConfig
         * always has proper defaults applied using EmbeddingUtils.ensureDefaultExecutionConfig().
         *
         * @return configured OpenAITextEmbedding instance
         * @throws IllegalStateException if required parameters are missing or invalid
         */
        public OpenAITextEmbedding build() {
            // Validate required parameters
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalStateException("apiKey is required and cannot be null or empty");
            }
            if (modelName == null || modelName.isEmpty()) {
                throw new IllegalStateException(
                        "modelName is required and cannot be null or empty");
            }
            if (dimensions != null && dimensions <= 0) {
                throw new IllegalStateException("dimensions must be positive, got: " + dimensions);
            }

            ExecutionConfig effectiveConfig =
                    EmbeddingUtils.ensureDefaultExecutionConfig(defaultExecutionConfig);

            return new OpenAITextEmbedding(apiKey, modelName, dimensions, effectiveConfig, baseUrl);
        }
    }
}
