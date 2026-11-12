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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.services.blocking.EmbeddingService;
import io.agentscope.core.Version;
import io.agentscope.core.embedding.EmbeddingException;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ExecutionConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import reactor.test.StepVerifier;

@Tag("unit")
class OpenAITextEmbeddingLifecycleTest {

    private final OpenAIOkHttpClient.Builder clientBuilder = mock(OpenAIOkHttpClient.Builder.class);
    private final OpenAIClient client = mock(OpenAIClient.class);
    private final EmbeddingService embeddings = mock(EmbeddingService.class);
    private final TextBlock text = TextBlock.builder().text("hello").build();
    private OpenAITextEmbedding model;
    private CreateEmbeddingResponse response;

    @AfterEach
    void tearDown() {
        if (model != null) {
            model.close();
        }
    }

    private void createModel(int maxAttempts) {
        createModel(ExecutionConfig.builder().maxAttempts(maxAttempts).build());
    }

    private void createModel(ExecutionConfig executionConfig) {
        Embedding embedding = mock(Embedding.class);
        when(embedding.embedding()).thenReturn(List.of(0.1f, 0.2f, 0.3f));
        response = mock(CreateEmbeddingResponse.class);
        when(response.data()).thenReturn(List.of(embedding));
        when(clientBuilder.build()).thenReturn(client);
        when(client.embeddings()).thenReturn(embeddings);
        when(embeddings.create(any(EmbeddingCreateParams.class))).thenReturn(response);
        model =
                OpenAITextEmbedding.builder()
                        .apiKey("test-key")
                        .baseUrl("http://127.0.0.1:1/v1")
                        .modelName("text-embedding-3-small")
                        .dimensions(3)
                        .executionConfig(executionConfig)
                        .build();
    }

    private MockedStatic<OpenAIOkHttpClient> mockClientBuilder() {
        MockedStatic<OpenAIOkHttpClient> mocked = Mockito.mockStatic(OpenAIOkHttpClient.class);
        mocked.when(OpenAIOkHttpClient::builder).thenReturn(clientBuilder);
        return mocked;
    }

    @Test
    void reusesClientAcrossCallsAndSubscriptions() {
        createModel(1);
        try (MockedStatic<OpenAIOkHttpClient> mocked = mockClientBuilder()) {
            var pending = model.embed(text);
            mocked.verifyNoInteractions();
            for (int i = 0; i < 100; i++) {
                assertNotNull(pending.block(Duration.ofSeconds(5)));
                assertNotNull(model.embed(text).block(Duration.ofSeconds(5)));
            }
            mocked.verify(OpenAIOkHttpClient::builder, times(1));
            verify(clientBuilder, times(1)).build();
            verify(clientBuilder).apiKey("test-key");
            verify(clientBuilder).baseUrl("http://127.0.0.1:1/v1");
            verify(clientBuilder).putHeader("User-Agent", Version.getUserAgent());
            verify(embeddings, times(200)).create(any(EmbeddingCreateParams.class));
        }
    }

    @Test
    void initializesOnlyOneClientDuringConcurrentFirstUse() throws Exception {
        createModel(1);
        int workers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                results.add(
                        executor.submit(
                                () -> {
                                    // Mockito static mocks are thread-local; install one on every
                                    // caller thread.
                                    try (MockedStatic<OpenAIOkHttpClient> ignored =
                                            mockClientBuilder()) {
                                        ready.countDown();
                                        assertTrue(start.await(10, TimeUnit.SECONDS));
                                        for (int request = 0; request < 10; request++) {
                                            assertNotNull(
                                                    model.embed(text).block(Duration.ofSeconds(5)));
                                        }
                                    }
                                    return null;
                                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> result : results) {
                result.get(15, TimeUnit.SECONDS);
            }
            verify(clientBuilder, times(1)).build();
            verify(embeddings, times(80)).create(any(EmbeddingCreateParams.class));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void retryReusesTheInitializedClient() {
        createModel(2);
        when(embeddings.create(any(EmbeddingCreateParams.class)))
                .thenThrow(new IllegalStateException("temporary failure"))
                .thenReturn(response);
        try (MockedStatic<OpenAIOkHttpClient> mocked = mockClientBuilder()) {
            assertNotNull(model.embed(text).block(Duration.ofSeconds(10)));
            mocked.verify(OpenAIOkHttpClient::builder, times(1));
            verify(clientBuilder, times(1)).build();
            verify(embeddings, times(2)).create(any(EmbeddingCreateParams.class));
        }
    }

    @Test
    void closeReleasesClientOnceAndRejectsPendingSubscriptions() {
        createModel(1);
        try (MockedStatic<OpenAIOkHttpClient> mocked = mockClientBuilder()) {
            assertNotNull(model.embed(text).block(Duration.ofSeconds(5)));
            var pending = model.embed(text);
            model.close();
            model.close();
            verify(client, times(1)).close();
            EmbeddingException error = assertThrows(EmbeddingException.class, pending::block);
            assertTrue(error.getMessage().contains("closed"));
            mocked.verify(OpenAIOkHttpClient::builder, times(1));
            verify(embeddings, times(1)).create(any(EmbeddingCreateParams.class));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closedModelDoesNotRetryEvenWhenRetriesAreEnabled(boolean initialized) {
        AtomicInteger retryDecisions = new AtomicInteger();
        createModel(
                ExecutionConfig.builder()
                        .maxAttempts(2)
                        .initialBackoff(Duration.ofMillis(10))
                        .maxBackoff(Duration.ofMillis(10))
                        .retryOn(
                                error -> {
                                    retryDecisions.incrementAndGet();
                                    return true;
                                })
                        .build());
        try (MockedStatic<OpenAIOkHttpClient> mocked = mockClientBuilder()) {
            if (initialized) {
                assertNotNull(model.embed(text).block(Duration.ofSeconds(5)));
            }
            // Creating a Mono before close must not bypass the closed-state check at subscription.
            var pending = model.embed(text);
            model.close();

            StepVerifier.withVirtualTime(() -> pending)
                    .thenAwait(Duration.ofSeconds(1))
                    .expectErrorSatisfies(
                            error ->
                                    assertTrue(
                                            assertInstanceOf(EmbeddingException.class, error)
                                                    .getMessage()
                                                    .contains("closed")))
                    .verify(Duration.ofSeconds(5));
            StepVerifier.withVirtualTime(() -> model.embed(text))
                    .thenAwait(Duration.ofSeconds(1))
                    .expectErrorSatisfies(
                            error ->
                                    assertTrue(
                                            assertInstanceOf(EmbeddingException.class, error)
                                                    .getMessage()
                                                    .contains("closed")))
                    .verify(Duration.ofSeconds(5));

            assertEquals(0, retryDecisions.get(), "Closed models must bypass the retry pipeline");
            if (initialized) {
                mocked.verify(OpenAIOkHttpClient::builder, times(1));
                verify(clientBuilder, times(1)).build();
                verify(embeddings, times(1)).create(any(EmbeddingCreateParams.class));
                verify(client, times(1)).close();
            } else {
                mocked.verifyNoInteractions();
                Mockito.verifyNoInteractions(clientBuilder, client, embeddings);
            }
        }
    }

    @Test
    void closingUnusedModelDoesNotCreateClient() {
        model =
                OpenAITextEmbedding.builder()
                        .apiKey("test-key")
                        .modelName("test-model")
                        .executionConfig(ExecutionConfig.builder().maxAttempts(1).build())
                        .build();
        try (MockedStatic<OpenAIOkHttpClient> mocked = mockClientBuilder()) {
            var pending = model.embed(text);
            model.close();
            model.close();
            assertThrows(EmbeddingException.class, pending::block);
            mocked.verifyNoInteractions();
        }
    }
}
