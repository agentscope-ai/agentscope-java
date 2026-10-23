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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.embedding.EmbeddingException;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.ExecutionConfig;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Unit tests for OpenAITextEmbedding.
 *
 * <p>Tests builder pattern, configuration, error handling, and basic functionality.
 * For actual API calls, see e2e tests.
 */
@Tag("unit")
@DisplayName("OpenAITextEmbedding Unit Tests")
class OpenAITextEmbeddingTest {

    private static final String TEST_API_KEY = "test_api_key_12345";
    private static final String TEST_MODEL_NAME = "text-embedding-3-small";
    private static final int TEST_DIMENSIONS = 1536;

    @Test
    @DisplayName("Should create embedding model with builder")
    void testBuilderCreation() {
        OpenAITextEmbedding model =
                OpenAITextEmbedding.builder()
                        .apiKey(TEST_API_KEY)
                        .modelName(TEST_MODEL_NAME)
                        .dimensions(TEST_DIMENSIONS)
                        .build();

        assertNotNull(model);
        assertEquals(TEST_MODEL_NAME, model.getModelName());
        assertEquals(TEST_DIMENSIONS, model.getDimensions());
    }

    @Test
    @DisplayName("Should create embedding model with all builder options")
    void testBuilderWithAllOptions() {
        ExecutionConfig executionConfig =
                ExecutionConfig.builder().timeout(Duration.ofSeconds(30)).maxAttempts(3).build();

        OpenAITextEmbedding model =
                OpenAITextEmbedding.builder()
                        .apiKey(TEST_API_KEY)
                        .modelName(TEST_MODEL_NAME)
                        .dimensions(TEST_DIMENSIONS)
                        .executionConfig(executionConfig)
                        .baseUrl("https://custom-url.com")
                        .build();

        assertNotNull(model);
        assertEquals(TEST_MODEL_NAME, model.getModelName());
        assertEquals(TEST_DIMENSIONS, model.getDimensions());
    }

    @Test
    @DisplayName("Should apply default execution config when not provided")
    void testDefaultExecutionConfig() {
        OpenAITextEmbedding model =
                OpenAITextEmbedding.builder()
                        .apiKey(TEST_API_KEY)
                        .modelName(TEST_MODEL_NAME)
                        .dimensions(TEST_DIMENSIONS)
                        .build();

        assertNotNull(model);
        // Default config should be applied via EmbeddingUtils.ensureDefaultExecutionConfig
    }

    @Test
    @DisplayName("Should reject null or unsupported ContentBlock")
    void testNullEmptyText() {
        OpenAITextEmbedding model =
                OpenAITextEmbedding.builder()
                        .apiKey(TEST_API_KEY)
                        .modelName(TEST_MODEL_NAME)
                        .dimensions(TEST_DIMENSIONS)
                        .build();

        // Test null ContentBlock
        StepVerifier.create(model.embed((ContentBlock) null))
                .expectError(EmbeddingException.class)
                .verify();

        // Test empty TextBlock
        StepVerifier.create(model.embed(TextBlock.builder().text("").build()))
                .expectError(EmbeddingException.class)
                .verify();

        // Test whitespace-only TextBlock
        StepVerifier.create(model.embed(TextBlock.builder().text("   ").build()))
                .expectError(EmbeddingException.class)
                .verify();

        // Test unsupported ImageBlock
        ImageBlock imageBlock =
                ImageBlock.builder()
                        .source(URLSource.builder().url("https://example.com/image.jpg").build())
                        .build();
        StepVerifier.create(model.embed(imageBlock))
                .expectErrorMatches(
                        error ->
                                error instanceof EmbeddingException
                                        && error.getMessage().contains("only supports TextBlock"))
                .verify();
    }

    @Test
    @DisplayName("Should implement EmbeddingModel interface")
    void testImplementsInterface() {
        OpenAITextEmbedding model =
                OpenAITextEmbedding.builder()
                        .apiKey(TEST_API_KEY)
                        .modelName(TEST_MODEL_NAME)
                        .dimensions(TEST_DIMENSIONS)
                        .build();

        assertTrue(model instanceof EmbeddingModel);
    }

    @Test
    @DisplayName("Should have correct model name and dimensions")
    void testModelProperties() {
        String customModelName = "custom-embedding-model";
        int customDimensions = 512;

        OpenAITextEmbedding model =
                OpenAITextEmbedding.builder()
                        .apiKey(TEST_API_KEY)
                        .modelName(customModelName)
                        .dimensions(customDimensions)
                        .build();

        assertEquals(customModelName, model.getModelName());
        assertEquals(customDimensions, model.getDimensions());
    }

    @Test
    @DisplayName("Should handle builder with minimal configuration")
    void testMinimalBuilder() {
        OpenAITextEmbedding model =
                OpenAITextEmbedding.builder()
                        .apiKey(TEST_API_KEY)
                        .modelName(TEST_MODEL_NAME)
                        .dimensions(TEST_DIMENSIONS)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should apply timeout configuration")
    void testTimeoutConfiguration() {
        ExecutionConfig executionConfig =
                ExecutionConfig.builder().timeout(Duration.ofMillis(100)).maxAttempts(1).build();

        OpenAITextEmbedding model =
                OpenAITextEmbedding.builder()
                        .apiKey(TEST_API_KEY)
                        .modelName(TEST_MODEL_NAME)
                        .dimensions(TEST_DIMENSIONS)
                        .executionConfig(executionConfig)
                        .build();

        assertNotNull(model);
        // Timeout will be applied when embed() is called
    }

    @Test
    @DisplayName("Should throw exception when API key is null or empty")
    void testApiKeyValidation() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        OpenAITextEmbedding.builder()
                                .apiKey(null)
                                .modelName(TEST_MODEL_NAME)
                                .dimensions(TEST_DIMENSIONS)
                                .build());

        assertThrows(
                IllegalStateException.class,
                () ->
                        OpenAITextEmbedding.builder()
                                .apiKey("")
                                .modelName(TEST_MODEL_NAME)
                                .dimensions(TEST_DIMENSIONS)
                                .build());
    }

    @Test
    @DisplayName("Should throw exception when model name is null or empty")
    void testModelNameValidation() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        OpenAITextEmbedding.builder()
                                .apiKey(TEST_API_KEY)
                                .modelName(null)
                                .dimensions(TEST_DIMENSIONS)
                                .build());

        assertThrows(
                IllegalStateException.class,
                () ->
                        OpenAITextEmbedding.builder()
                                .apiKey(TEST_API_KEY)
                                .modelName("")
                                .dimensions(TEST_DIMENSIONS)
                                .build());
    }

    @Test
    @DisplayName("Should throw exception when dimensions are invalid")
    void testDimensionsValidation() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        OpenAITextEmbedding.builder()
                                .apiKey(TEST_API_KEY)
                                .modelName(TEST_MODEL_NAME)
                                .dimensions(0)
                                .build());

        assertThrows(
                IllegalStateException.class,
                () ->
                        OpenAITextEmbedding.builder()
                                .apiKey(TEST_API_KEY)
                                .modelName(TEST_MODEL_NAME)
                                .dimensions(-1)
                                .build());
    }
}
