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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.embedding.EmbeddingException;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ExecutionConfig;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Unit tests for DashScopeMultiModalEmbedding.
 *
 * <p>Tests builder pattern, configuration, error handling, and basic functionality.
 * For actual API calls, see e2e tests.
 */
@Tag("unit")
@DisplayName("DashScopeMultiModalEmbedding Unit Tests")
class DashScopeMultiModalEmbeddingTest {

    private static final String TEST_API_KEY = "test_api_key_12345";
    private static final String TEST_MODEL_NAME = "multimodal-embedding-v1";
    private static final int TEST_DIMENSIONS = 1024;

    @Test
    @DisplayName("Should create embedding model with builder")
    void testBuilderCreation() {
        DashScopeMultiModalEmbedding model =
                DashScopeMultiModalEmbedding.builder()
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

        DashScopeMultiModalEmbedding model =
                DashScopeMultiModalEmbedding.builder()
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
        DashScopeMultiModalEmbedding model =
                DashScopeMultiModalEmbedding.builder()
                        .apiKey(TEST_API_KEY)
                        .modelName(TEST_MODEL_NAME)
                        .dimensions(TEST_DIMENSIONS)
                        .build();

        assertNotNull(model);
        // Default config should be applied via EmbeddingUtils.ensureDefaultExecutionConfig
    }

    @Test
    @DisplayName("Should reject null or unsupported ContentBlock")
    void testNullEmptyInput() {
        DashScopeMultiModalEmbedding model =
                DashScopeMultiModalEmbedding.builder()
                        .apiKey(TEST_API_KEY)
                        .modelName(TEST_MODEL_NAME)
                        .dimensions(TEST_DIMENSIONS)
                        .build();

        StepVerifier.create(model.embed((ContentBlock) null))
                .expectErrorMatches(
                        error ->
                                (error instanceof EmbeddingException
                                                && error.getMessage().contains("cannot be null"))
                                        || (error.getCause() instanceof EmbeddingException
                                                && error.getCause()
                                                        .getMessage()
                                                        .contains("cannot be null")))
                .verify();

        StepVerifier.create(model.embed(TextBlock.builder().text("").build()))
                .expectErrorMatches(
                        error ->
                                (error instanceof EmbeddingException
                                                && error.getMessage()
                                                        .contains("cannot be null or empty"))
                                        || (error.getCause() instanceof EmbeddingException
                                                && error.getCause()
                                                        .getMessage()
                                                        .contains("cannot be null or empty")))
                .verify();

        StepVerifier.create(model.embed(TextBlock.builder().text("   ").build()))
                .expectErrorMatches(
                        error ->
                                (error instanceof EmbeddingException
                                                && error.getMessage()
                                                        .contains("cannot be null or empty"))
                                        || (error.getCause() instanceof EmbeddingException
                                                && error.getCause()
                                                        .getMessage()
                                                        .contains("cannot be null or empty")))
                .verify();
    }
}
