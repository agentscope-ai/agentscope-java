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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.embedding.EmbeddingException;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.ExecutionConfig;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * Unit tests for DashScopeTextEmbedding.
 *
 * <p>Tests builder pattern, configuration, error handling, and basic functionality.
 * For actual API calls, see e2e tests.
 */
@Tag("unit")
@DisplayName("DashScopeTextEmbedding Unit Tests")
class DashScopeTextEmbeddingTest {

    private static final String TEST_API_KEY = "test_api_key_12345";
    private static final String TEST_MODEL_NAME = "text-embedding-v3";
    private static final int TEST_DIMENSIONS = 1024;

    @Test
    @DisplayName("Should create embedding model with builder")
    void testBuilderCreation() {
        DashScopeTextEmbedding model =
                DashScopeTextEmbedding.builder()
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

        DashScopeTextEmbedding model =
                DashScopeTextEmbedding.builder()
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
        DashScopeTextEmbedding model =
                DashScopeTextEmbedding.builder()
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
        DashScopeTextEmbedding model =
                DashScopeTextEmbedding.builder()
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
        DashScopeTextEmbedding model =
                DashScopeTextEmbedding.builder()
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

        DashScopeTextEmbedding model =
                DashScopeTextEmbedding.builder()
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
        DashScopeTextEmbedding model =
                DashScopeTextEmbedding.builder()
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

        DashScopeTextEmbedding model =
                DashScopeTextEmbedding.builder()
                        .apiKey(TEST_API_KEY)
                        .modelName(TEST_MODEL_NAME)
                        .dimensions(TEST_DIMENSIONS)
                        .executionConfig(executionConfig)
                        .build();

        assertNotNull(model);
        // Timeout will be applied when embed() is called
    }

    @Test
    @DisplayName("Should run synchronous SDK calls off non-blocking subscriber threads")
    void synchronousSdkCallRunsOffNonBlockingSubscriberThread() throws Exception {
        MockWebServer server = new MockWebServer();
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        server.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request)
                            throws InterruptedException {
                        requestReceived.countDown();
                        releaseResponse.await();
                        return new MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json")
                                .setBody(
                                        "{\"output\":{\"embeddings\":[{\"embedding\":[0.25],"
                                                + "\"text_index\":0}]},\"request_id\":\"test\"}");
                    }
                });
        server.start();

        Scheduler subscriberScheduler = Schedulers.newSingle("embedding-subscriber");
        CountDownLatch markerRan = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<double[]> embedding = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Disposable subscription = null;
        boolean requestObserved;
        boolean markerObserved;
        try {
            DashScopeTextEmbedding model =
                    DashScopeTextEmbedding.builder()
                            .apiKey(TEST_API_KEY)
                            .modelName(TEST_MODEL_NAME)
                            .dimensions(1)
                            .baseUrl(server.url("/").toString())
                            .build();
            subscription =
                    model.embed(TextBlock.builder().text("hello").build())
                            .subscribeOn(subscriberScheduler)
                            .subscribe(
                                    embedding::set,
                                    throwable -> {
                                        error.set(throwable);
                                        completed.countDown();
                                    },
                                    completed::countDown);

            requestObserved = requestReceived.await(5, TimeUnit.SECONDS);
            subscriberScheduler.schedule(markerRan::countDown);
            markerObserved = markerRan.await(5, TimeUnit.SECONDS);
        } finally {
            releaseResponse.countDown();
            completed.await(5, TimeUnit.SECONDS);
            if (subscription != null) {
                subscription.dispose();
            }
            subscriberScheduler.dispose();
            server.shutdown();
        }

        assertTrue(requestObserved, "The local server should receive the SDK request");
        assertTrue(
                markerObserved,
                "The synchronous DashScope SDK call must not block the subscriber scheduler");
        assertNull(error.get());
        assertNotNull(embedding.get());
        assertEquals(0.25, embedding.get()[0]);
    }
}
