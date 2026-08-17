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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.test.StepVerifier;

/**
 * End-to-end tests for OpenAITextEmbedding.
 *
 * <p>These tests require a valid OPENAI_API_KEY environment variable and make actual
 * API calls to OpenAI embedding service.
 *
 * <p>Tagged as "e2e" - requires external API access.
 */
@Tag("e2e")
@DisplayName("OpenAITextEmbedding E2E Tests")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class OpenAITextEmbeddingE2ETest {

    private static final String MODEL_NAME = "text-embedding-3-small";
    private static final int EXPECTED_DIMENSIONS = 1536;

    private EmbeddingModel createModel() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        return OpenAITextEmbedding.builder()
                .apiKey(apiKey)
                .modelName(MODEL_NAME)
                .dimensions(EXPECTED_DIMENSIONS)
                .build();
    }

    @Test
    @DisplayName("Should generate embedding for single text")
    void testSingleTextEmbedding() {
        EmbeddingModel model = createModel();

        TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();
        StepVerifier.create(model.embed(textBlock))
                .assertNext(
                        embedding -> {
                            assertNotNull(embedding, "Embedding should not be null");
                            assertEquals(
                                    EXPECTED_DIMENSIONS,
                                    embedding.length,
                                    "Embedding dimension should match");
                            // Verify embedding values are reasonable (not all zeros)
                            boolean hasNonZero = false;
                            for (double value : embedding) {
                                if (Math.abs(value) > 0.0001) {
                                    hasNonZero = true;
                                    break;
                                }
                            }
                            assertTrue(hasNonZero, "Embedding should have non-zero values");
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should generate consistent embeddings for same text")
    void testEmbeddingConsistency() {
        EmbeddingModel model = createModel();
        TextBlock textBlock = TextBlock.builder().text("Test text for consistency").build();

        double[] embedding1 = model.embed(textBlock).block();
        double[] embedding2 = model.embed(textBlock).block();

        assertNotNull(embedding1);
        assertNotNull(embedding2);
        assertEquals(embedding1.length, embedding2.length);

        // Embeddings should be similar (cosine similarity should be high)
        // Note: Some embedding models may have slight variations, so we check they're close
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        for (int i = 0; i < embedding1.length; i++) {
            dotProduct += embedding1[i] * embedding2[i];
            norm1 += embedding1[i] * embedding1[i];
            norm2 += embedding2[i] * embedding2[i];
        }
        double cosineSimilarity = dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
        assertTrue(
                cosineSimilarity > 0.9,
                "Embeddings for same text should be very similar, cosine similarity: "
                        + cosineSimilarity);
    }

    @Test
    @DisplayName("Should return correct model name and dimensions")
    void testModelProperties() {
        EmbeddingModel model = createModel();

        assertEquals(MODEL_NAME, model.getModelName());
        assertEquals(EXPECTED_DIMENSIONS, model.getDimensions());
    }

    @Test
    @DisplayName("Should handle special characters and Unicode text")
    void testSpecialCharactersAndUnicode() {
        EmbeddingModel model = createModel();
        TextBlock textBlock =
                TextBlock.builder()
                        .text("Hello 世界! Special chars: @#$%^&*()_+-=[]{}|;':\",./<>?`~")
                        .build();

        StepVerifier.create(model.embed(textBlock))
                .assertNext(
                        embedding -> {
                            assertNotNull(embedding, "Embedding should not be null");
                            assertEquals(
                                    EXPECTED_DIMENSIONS,
                                    embedding.length,
                                    "Embedding dimension should match");
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle long text input")
    void testLongTextInput() {
        EmbeddingModel model = createModel();
        // Create a long text
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longText.append("This is a test sentence number ").append(i).append(". ");
        }

        TextBlock textBlock = TextBlock.builder().text(longText.toString()).build();

        StepVerifier.create(model.embed(textBlock))
                .assertNext(
                        embedding -> {
                            assertNotNull(embedding, "Embedding should not be null");
                            assertEquals(
                                    EXPECTED_DIMENSIONS,
                                    embedding.length,
                                    "Embedding dimension should match");
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle empty spaces in text")
    void testTextWithSpaces() {
        EmbeddingModel model = createModel();
        TextBlock textBlock = TextBlock.builder().text("   spaced text   ").build();

        StepVerifier.create(model.embed(textBlock))
                .assertNext(
                        embedding -> {
                            assertNotNull(embedding, "Embedding should not be null");
                            assertEquals(
                                    EXPECTED_DIMENSIONS,
                                    embedding.length,
                                    "Embedding dimension should match");
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle different embedding dimensions")
    void testDifferentEmbeddingDimensions() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        // Test with smaller dimensions
        int smallDimensions = 512;
        EmbeddingModel model =
                OpenAITextEmbedding.builder()
                        .apiKey(apiKey)
                        .modelName(MODEL_NAME)
                        .dimensions(smallDimensions)
                        .build();

        TextBlock textBlock = TextBlock.builder().text("Test text with custom dimensions").build();

        StepVerifier.create(model.embed(textBlock))
                .assertNext(
                        embedding -> {
                            assertNotNull(embedding, "Embedding should not be null");
                            assertEquals(
                                    smallDimensions,
                                    embedding.length,
                                    "Embedding dimension should match the custom dimension");
                        })
                .verifyComplete();
    }
}
