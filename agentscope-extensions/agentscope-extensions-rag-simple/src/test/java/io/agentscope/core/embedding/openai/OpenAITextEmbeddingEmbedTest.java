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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import io.agentscope.core.embedding.EmbeddingException;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ExecutionConfig;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import reactor.test.StepVerifier;

/**
 * Tests specifically for the embed method of OpenAITextEmbedding to improve code coverage.
 */
@Tag("unit")
@DisplayName("OpenAITextEmbedding Embed Method Tests")
class OpenAITextEmbeddingEmbedTest {

    private static final String TEST_MODEL_NAME = "text-embedding-3-small";
    private static final int TEST_DIMENSIONS = 1536;

    private OpenAITextEmbedding model;

    @BeforeEach
    void setUp() {
        // 使用真实的 OpenAITextEmbedding 实例，避免 mock 掉被测方法
        model =
                OpenAITextEmbedding.builder()
                        .apiKey("mock_api_key")
                        .modelName(TEST_MODEL_NAME)
                        .dimensions(TEST_DIMENSIONS)
                        .executionConfig(ExecutionConfig.builder().maxAttempts(1).build())
                        .build();
    }

    @Test
    @DisplayName("Should successfully generate embedding for valid text")
    void testSuccessfulEmbeddingGeneration() {
        // Prepare mock response
        Embedding mockEmbedding = mock(Embedding.class);
        when(mockEmbedding.embedding()).thenReturn(Arrays.asList(0.1f, 0.2f, 0.3f));

        CreateEmbeddingResponse mockResponse = mock(CreateEmbeddingResponse.class);
        when(mockResponse.data()).thenReturn(Arrays.asList(mockEmbedding));

        // Mock the OpenAI client static builder -> builder -> client -> embeddings chain
        try (MockedStatic<com.openai.client.okhttp.OpenAIOkHttpClient> mockedClient =
                Mockito.mockStatic(com.openai.client.okhttp.OpenAIOkHttpClient.class)) {

            com.openai.client.okhttp.OpenAIOkHttpClient.Builder mockBuilder =
                    mock(com.openai.client.okhttp.OpenAIOkHttpClient.Builder.class);
            com.openai.client.OpenAIClient mockOpenAIClient =
                    mock(com.openai.client.OpenAIClient.class);
            com.openai.services.blocking.EmbeddingService mockEmbeddings =
                    mock(com.openai.services.blocking.EmbeddingService.class);

            when(mockBuilder.build()).thenReturn(mockOpenAIClient);
            when(mockBuilder.apiKey(any())).thenReturn(mockBuilder);
            when(mockBuilder.baseUrl(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.putHeader(any(), any())).thenReturn(mockBuilder);

            mockedClient
                    .when(com.openai.client.okhttp.OpenAIOkHttpClient::builder)
                    .thenReturn(mockBuilder);

            when(mockOpenAIClient.embeddings()).thenReturn(mockEmbeddings);
            when(mockEmbeddings.create(any(EmbeddingCreateParams.class))).thenReturn(mockResponse);

            TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();

            StepVerifier.create(model.embed(textBlock))
                    .assertNext(
                            embedding -> {
                                assertNotNull(embedding);
                                // 返回的向量长度等于返回数据长度（3），覆盖维度不匹配分支
                                assertEquals(3, embedding.length);
                                assertEquals(0.1, embedding[0], 0.001);
                                assertEquals(0.2, embedding[1], 0.001);
                                assertEquals(0.3, embedding[2], 0.001);
                            })
                    .verifyComplete();
        }
    }

    @Test
    @DisplayName("Should handle null response from API")
    void testNullApiResponse() {
        // Mock the OpenAI client static builder -> builder -> client -> embeddings chain
        try (MockedStatic<com.openai.client.okhttp.OpenAIOkHttpClient> mockedClient =
                Mockito.mockStatic(com.openai.client.okhttp.OpenAIOkHttpClient.class)) {

            com.openai.client.okhttp.OpenAIOkHttpClient.Builder mockBuilder =
                    mock(com.openai.client.okhttp.OpenAIOkHttpClient.Builder.class);
            com.openai.client.OpenAIClient mockOpenAIClient =
                    mock(com.openai.client.OpenAIClient.class);
            com.openai.services.blocking.EmbeddingService mockEmbeddings =
                    mock(com.openai.services.blocking.EmbeddingService.class);

            when(mockBuilder.build()).thenReturn(mockOpenAIClient);
            when(mockBuilder.apiKey(any())).thenReturn(mockBuilder);
            when(mockBuilder.baseUrl(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.putHeader(any(), any())).thenReturn(mockBuilder);

            mockedClient
                    .when(com.openai.client.okhttp.OpenAIOkHttpClient::builder)
                    .thenReturn(mockBuilder);

            when(mockOpenAIClient.embeddings()).thenReturn(mockEmbeddings);
            when(mockEmbeddings.create(any(EmbeddingCreateParams.class))).thenReturn(null);

            TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();

            StepVerifier.create(model.embed(textBlock))
                    .expectErrorSatisfies(
                            throwable -> {
                                assertTrue(throwable instanceof EmbeddingException);
                                assertTrue(
                                        throwable
                                                .getMessage()
                                                .contains(
                                                        "Empty response from OpenAI embedding"
                                                                + " API"));
                            })
                    .verify();
        }
    }

    @Test
    @DisplayName("Should handle null data in response")
    void testNullDataInResponse() {
        // Prepare mock response with null data
        CreateEmbeddingResponse mockResponse = mock(CreateEmbeddingResponse.class);
        when(mockResponse.data()).thenReturn(null);

        // Mock the OpenAI client static builder -> builder -> client -> embeddings chain
        try (MockedStatic<com.openai.client.okhttp.OpenAIOkHttpClient> mockedClient =
                Mockito.mockStatic(com.openai.client.okhttp.OpenAIOkHttpClient.class)) {

            com.openai.client.okhttp.OpenAIOkHttpClient.Builder mockBuilder =
                    mock(com.openai.client.okhttp.OpenAIOkHttpClient.Builder.class);
            com.openai.client.OpenAIClient mockOpenAIClient =
                    mock(com.openai.client.OpenAIClient.class);
            com.openai.services.blocking.EmbeddingService mockEmbeddings =
                    mock(com.openai.services.blocking.EmbeddingService.class);

            when(mockBuilder.build()).thenReturn(mockOpenAIClient);
            when(mockBuilder.apiKey(any())).thenReturn(mockBuilder);
            when(mockBuilder.baseUrl(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.putHeader(any(), any())).thenReturn(mockBuilder);

            mockedClient
                    .when(com.openai.client.okhttp.OpenAIOkHttpClient::builder)
                    .thenReturn(mockBuilder);

            when(mockOpenAIClient.embeddings()).thenReturn(mockEmbeddings);
            when(mockEmbeddings.create(any(EmbeddingCreateParams.class))).thenReturn(mockResponse);

            TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();

            StepVerifier.create(model.embed(textBlock))
                    .expectErrorSatisfies(
                            throwable -> {
                                assertTrue(throwable instanceof EmbeddingException);
                                assertTrue(
                                        throwable
                                                .getMessage()
                                                .contains(
                                                        "Empty response from OpenAI embedding"
                                                                + " API"));
                            })
                    .verify();
        }
    }

    @Test
    @DisplayName("Should handle empty data list in response")
    void testEmptyDataListInResponse() {
        // Prepare mock response with empty data
        CreateEmbeddingResponse mockResponse = mock(CreateEmbeddingResponse.class);
        when(mockResponse.data()).thenReturn(List.of());

        // Mock the OpenAI client static builder -> builder -> client -> embeddings chain
        try (MockedStatic<com.openai.client.okhttp.OpenAIOkHttpClient> mockedClient =
                Mockito.mockStatic(com.openai.client.okhttp.OpenAIOkHttpClient.class)) {

            com.openai.client.okhttp.OpenAIOkHttpClient.Builder mockBuilder =
                    mock(com.openai.client.okhttp.OpenAIOkHttpClient.Builder.class);
            com.openai.client.OpenAIClient mockOpenAIClient =
                    mock(com.openai.client.OpenAIClient.class);
            com.openai.services.blocking.EmbeddingService mockEmbeddings =
                    mock(com.openai.services.blocking.EmbeddingService.class);

            when(mockBuilder.build()).thenReturn(mockOpenAIClient);
            when(mockBuilder.apiKey(any())).thenReturn(mockBuilder);
            when(mockBuilder.baseUrl(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.putHeader(any(), any())).thenReturn(mockBuilder);

            mockedClient
                    .when(com.openai.client.okhttp.OpenAIOkHttpClient::builder)
                    .thenReturn(mockBuilder);

            when(mockOpenAIClient.embeddings()).thenReturn(mockEmbeddings);
            when(mockEmbeddings.create(any(EmbeddingCreateParams.class))).thenReturn(mockResponse);

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
    @DisplayName("Should handle null embedding in response")
    void testNullEmbeddingInResponse() {
        // Prepare mock response with null embedding
        Embedding mockEmbedding = mock(Embedding.class);
        when(mockEmbedding.embedding()).thenReturn(null);

        CreateEmbeddingResponse mockResponse = mock(CreateEmbeddingResponse.class);
        when(mockResponse.data()).thenReturn(Arrays.asList(mockEmbedding));

        // Mock the OpenAI client static builder -> builder -> client -> embeddings chain
        try (MockedStatic<com.openai.client.okhttp.OpenAIOkHttpClient> mockedClient =
                Mockito.mockStatic(com.openai.client.okhttp.OpenAIOkHttpClient.class)) {

            com.openai.client.okhttp.OpenAIOkHttpClient.Builder mockBuilder =
                    mock(com.openai.client.okhttp.OpenAIOkHttpClient.Builder.class);
            com.openai.client.OpenAIClient mockOpenAIClient =
                    mock(com.openai.client.OpenAIClient.class);
            com.openai.services.blocking.EmbeddingService mockEmbeddings =
                    mock(com.openai.services.blocking.EmbeddingService.class);

            when(mockBuilder.build()).thenReturn(mockOpenAIClient);
            when(mockBuilder.apiKey(any())).thenReturn(mockBuilder);
            when(mockBuilder.baseUrl(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.putHeader(any(), any())).thenReturn(mockBuilder);

            mockedClient
                    .when(com.openai.client.okhttp.OpenAIOkHttpClient::builder)
                    .thenReturn(mockBuilder);

            when(mockOpenAIClient.embeddings()).thenReturn(mockEmbeddings);
            when(mockEmbeddings.create(any(EmbeddingCreateParams.class))).thenReturn(mockResponse);

            TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();

            StepVerifier.create(model.embed(textBlock))
                    .expectErrorSatisfies(
                            throwable -> {
                                assertTrue(throwable instanceof EmbeddingException);
                                assertTrue(
                                        throwable
                                                .getMessage()
                                                .contains("Empty embedding vector in response"));
                            })
                    .verify();
        }
    }

    @Test
    @DisplayName("Should handle empty embedding list in response")
    void testEmptyEmbeddingListInResponse() {
        // Prepare mock response with empty embedding
        Embedding mockEmbedding = mock(Embedding.class);
        when(mockEmbedding.embedding()).thenReturn(List.of());

        CreateEmbeddingResponse mockResponse = mock(CreateEmbeddingResponse.class);
        when(mockResponse.data()).thenReturn(Arrays.asList(mockEmbedding));

        // Mock the OpenAI client static builder -> builder -> client -> embeddings chain
        try (MockedStatic<com.openai.client.okhttp.OpenAIOkHttpClient> mockedClient =
                Mockito.mockStatic(com.openai.client.okhttp.OpenAIOkHttpClient.class)) {

            com.openai.client.okhttp.OpenAIOkHttpClient.Builder mockBuilder =
                    mock(com.openai.client.okhttp.OpenAIOkHttpClient.Builder.class);
            com.openai.client.OpenAIClient mockOpenAIClient =
                    mock(com.openai.client.OpenAIClient.class);
            com.openai.services.blocking.EmbeddingService mockEmbeddings =
                    mock(com.openai.services.blocking.EmbeddingService.class);

            when(mockBuilder.build()).thenReturn(mockOpenAIClient);
            when(mockBuilder.apiKey(any())).thenReturn(mockBuilder);
            when(mockBuilder.baseUrl(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.putHeader(any(), any())).thenReturn(mockBuilder);

            mockedClient
                    .when(com.openai.client.okhttp.OpenAIOkHttpClient::builder)
                    .thenReturn(mockBuilder);

            when(mockOpenAIClient.embeddings()).thenReturn(mockEmbeddings);
            when(mockEmbeddings.create(any(EmbeddingCreateParams.class))).thenReturn(mockResponse);

            TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();

            StepVerifier.create(model.embed(textBlock))
                    .expectErrorSatisfies(
                            throwable -> {
                                assertTrue(throwable instanceof EmbeddingException);
                                assertTrue(
                                        throwable
                                                .getMessage()
                                                .contains("Empty embedding vector in response"));
                            })
                    .verify();
        }
    }
}
