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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.embedding.EmbeddingException;
import io.agentscope.core.formatter.ollama.dto.OllamaEmbeddingResponse;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.OllamaHttpClient;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import reactor.test.StepVerifier;

/**
 * Tests specifically for the embed method of OllamaTextEmbedding to improve code coverage.
 */
@Tag("unit")
@DisplayName("OllamaTextEmbedding Embed Method Tests")
class OllamaTextEmbeddingEmbedTest {

    private static final String TEST_MODEL_NAME = "nomic-embed-text";
    private static final String TEST_BASE_URL = "http://192.168.2.2:11434";
    private static final int TEST_DIMENSIONS = 768;

    private OllamaTextEmbedding model;

    @BeforeEach
    void setUp() {
        model =
                OllamaTextEmbedding.builder()
                        .baseUrl(TEST_BASE_URL)
                        .modelName(TEST_MODEL_NAME)
                        .dimensions(TEST_DIMENSIONS)
                        .executionConfig(ExecutionConfig.builder().maxAttempts(1).build())
                        .build();
    }

    @Test
    @DisplayName("Should successfully generate embedding for valid text")
    void testSuccessfulEmbeddingGeneration() {
        // Prepare mock response
        float[] mockEmbeddingValues = {0.1f, 0.2f, 0.3f};
        List<float[]> mockEmbeddings = Arrays.asList(mockEmbeddingValues);

        OllamaEmbeddingResponse mockResponse = mock(OllamaEmbeddingResponse.class);
        when(mockResponse.getEmbeddings()).thenReturn(mockEmbeddings);
        when(mockResponse.getModel()).thenReturn(TEST_MODEL_NAME);

        // Mock the OllamaHttpClient construction
        try (MockedConstruction<OllamaHttpClient> mockedConstruction =
                Mockito.mockConstruction(
                        OllamaHttpClient.class,
                        (mock, context) -> {
                            // OllamaHttpClient constructor takes transport and baseUrl
                            if (context.arguments().size() == 2
                                    && context.arguments().get(1).equals(TEST_BASE_URL)) {
                                when(mock.embed(any())).thenReturn(mockResponse);
                            }
                        })) {

            TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();

            StepVerifier.create(model.embed(textBlock))
                    .assertNext(
                            embedding -> {
                                assertNotNull(embedding);
                                assertEquals(3, embedding.length);
                                assertEquals(0.1, embedding[0], 0.001);
                                assertEquals(0.2, embedding[1], 0.001);
                                assertEquals(0.3, embedding[2], 0.001);
                            })
                    .verifyComplete();

            // Verify that the constructor was called with the expected baseUrl
            assertTrue(!mockedConstruction.constructed().isEmpty());
        }
    }

    @Test
    @DisplayName("Should handle null response from API")
    void testNullApiResponse() {
        // Mock the OllamaHttpClient construction
        try (MockedConstruction<OllamaHttpClient> mockedConstruction =
                Mockito.mockConstruction(
                        OllamaHttpClient.class,
                        (mock, context) -> {
                            // OllamaHttpClient constructor takes transport and baseUrl
                            if (context.arguments().size() == 2
                                    && context.arguments().get(1).equals(TEST_BASE_URL)) {
                                when(mock.embed(any())).thenReturn(null);
                            }
                        })) {

            TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();

            StepVerifier.create(model.embed(textBlock))
                    .expectErrorSatisfies(
                            throwable -> {
                                assertTrue(throwable instanceof EmbeddingException);
                                assertTrue(
                                        throwable
                                                .getMessage()
                                                .contains(
                                                        "Empty response from Ollama embedding"
                                                                + " API"));
                            })
                    .verify();
        }
    }

    @Test
    @DisplayName("Should handle null embeddings in response")
    void testNullEmbeddingsInResponse() {
        // Prepare mock response with null embeddings
        OllamaEmbeddingResponse mockResponse = mock(OllamaEmbeddingResponse.class);
        when(mockResponse.getEmbeddings()).thenReturn(null);

        // Mock the OllamaHttpClient construction
        try (MockedConstruction<OllamaHttpClient> mockedConstruction =
                Mockito.mockConstruction(
                        OllamaHttpClient.class,
                        (mock, context) -> {
                            // OllamaHttpClient constructor takes transport and baseUrl
                            if (context.arguments().size() == 2
                                    && context.arguments().get(1).equals(TEST_BASE_URL)) {
                                when(mock.embed(any())).thenReturn(mockResponse);
                            }
                        })) {

            TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();

            StepVerifier.create(model.embed(textBlock))
                    .expectErrorSatisfies(
                            throwable -> {
                                assertTrue(throwable instanceof EmbeddingException);
                                assertTrue(
                                        throwable
                                                .getMessage()
                                                .contains(
                                                        "Empty response from Ollama embedding"
                                                                + " API"));
                            })
                    .verify();
        }
    }

    @Test
    @DisplayName("Should handle empty embeddings list in response")
    void testEmptyEmbeddingsListInResponse() {
        // Prepare mock response with empty embeddings
        OllamaEmbeddingResponse mockResponse = mock(OllamaEmbeddingResponse.class);
        when(mockResponse.getEmbeddings()).thenReturn(List.of());

        // Mock the OllamaHttpClient construction
        try (MockedConstruction<OllamaHttpClient> mockedConstruction =
                Mockito.mockConstruction(
                        OllamaHttpClient.class,
                        (mock, context) -> {
                            // OllamaHttpClient constructor takes transport and baseUrl
                            if (context.arguments().size() == 2
                                    && context.arguments().get(1).equals(TEST_BASE_URL)) {
                                when(mock.embed(any())).thenReturn(mockResponse);
                            }
                        })) {

            TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();

            StepVerifier.create(model.embed(textBlock))
                    .expectErrorSatisfies(
                            throwable -> {
                                assertTrue(throwable instanceof EmbeddingException);
                                assertTrue(
                                        throwable
                                                .getMessage()
                                                .contains("No embedding data in response"));
                            })
                    .verify();
        }
    }

    @Test
    @DisplayName("Should handle null first embedding in response")
    void testNullFirstEmbeddingInResponse() {
        // Prepare mock response with null first embedding
        List<float[]> mockEmbeddings = Arrays.asList((float[]) null);
        OllamaEmbeddingResponse mockResponse = mock(OllamaEmbeddingResponse.class);
        when(mockResponse.getEmbeddings()).thenReturn(mockEmbeddings);

        // Mock the OllamaHttpClient construction
        try (MockedConstruction<OllamaHttpClient> mockedConstruction =
                Mockito.mockConstruction(
                        OllamaHttpClient.class,
                        (mock, context) -> {
                            // OllamaHttpClient constructor takes transport and baseUrl
                            if (context.arguments().size() == 2
                                    && context.arguments().get(1).equals(TEST_BASE_URL)) {
                                when(mock.embed(any())).thenReturn(mockResponse);
                            }
                        })) {

            TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();

            StepVerifier.create(model.embed(textBlock))
                    .expectErrorSatisfies(
                            throwable -> {
                                assertTrue(throwable instanceof EmbeddingException);
                                assertTrue(
                                        throwable
                                                .getMessage()
                                                .contains("No embedding data in response"));
                            })
                    .verify();
        }
    }

    @Test
    @DisplayName("Should handle OllamaHttpException")
    void testOllamaHttpException() {
        // Mock the OllamaHttpClient construction to throw OllamaHttpException
        try (MockedConstruction<OllamaHttpClient> mockedConstruction =
                Mockito.mockConstruction(
                        OllamaHttpClient.class,
                        (mock, context) -> {
                            // OllamaHttpClient constructor takes transport and baseUrl
                            if (context.arguments().size() == 2
                                    && context.arguments().get(1).equals(TEST_BASE_URL)) {
                                when(mock.embed(any()))
                                        .thenThrow(
                                                new OllamaHttpClient.OllamaHttpException(
                                                        "API request failed",
                                                        500,
                                                        "Internal Server Error"));
                            }
                        })) {

            TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();

            StepVerifier.create(model.embed(textBlock))
                    .expectErrorSatisfies(
                            throwable -> {
                                assertTrue(throwable instanceof EmbeddingException);
                                assertTrue(
                                        throwable
                                                .getMessage()
                                                .contains("Ollama API error: API request failed"));
                            })
                    .verify();
        }
    }
}
