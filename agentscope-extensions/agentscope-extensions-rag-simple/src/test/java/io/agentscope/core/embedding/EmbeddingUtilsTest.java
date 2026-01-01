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
package io.agentscope.core.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.core.model.ExecutionConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for EmbeddingUtils.
 *
 * <p>Tests timeout, retry, and default configuration handling.
 */
@Tag("unit")
@DisplayName("EmbeddingUtils Unit Tests")
class EmbeddingUtilsTest {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingUtilsTest.class);

    @Test
    @DisplayName("Should return original Mono when config is null")
    void testNullConfig() {
        double[] testEmbedding = new double[] {0.1, 0.2, 0.3};
        Mono<double[]> originalMono = Mono.just(testEmbedding);

        Mono<double[]> result =
                EmbeddingUtils.applyTimeoutAndRetry(
                        originalMono, null, "test-model", "test-provider", log);

        StepVerifier.create(result).expectNext(testEmbedding).verifyComplete();
    }

    @Test
    @DisplayName("Should apply timeout when configured")
    void testTimeoutApplication() {
        ExecutionConfig config = ExecutionConfig.builder().timeout(Duration.ofMillis(100)).build();

        // Create a Mono that delays longer than timeout
        Mono<double[]> slowMono =
                Mono.just(new double[] {0.1, 0.2}).delayElement(Duration.ofMillis(200));

        Mono<double[]> result =
                EmbeddingUtils.applyTimeoutAndRetry(
                        slowMono, config, "test-model", "test-provider", log);

        StepVerifier.create(result).expectError(EmbeddingException.class).verify();
    }

    @Test
    @DisplayName("Should not timeout when request completes within timeout")
    void testNoTimeoutWhenFast() {
        ExecutionConfig config = ExecutionConfig.builder().timeout(Duration.ofSeconds(1)).build();

        double[] testEmbedding = new double[] {0.1, 0.2, 0.3};
        Mono<double[]> fastMono = Mono.just(testEmbedding);

        Mono<double[]> result =
                EmbeddingUtils.applyTimeoutAndRetry(
                        fastMono, config, "test-model", "test-provider", log);

        StepVerifier.create(result).expectNext(testEmbedding).verifyComplete();
    }

    @Test
    @DisplayName("Should apply retry when maxAttempts > 1")
    void testRetryApplication() {
        AtomicInteger attemptCount = new AtomicInteger(0);
        ExecutionConfig config =
                ExecutionConfig.builder()
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofMillis(10))
                        .maxBackoff(Duration.ofMillis(50))
                        .build();

        // Create a Mono that fails twice then succeeds
        Mono<double[]> retryableMono =
                Mono.fromCallable(
                                () -> {
                                    int attempt = attemptCount.incrementAndGet();
                                    if (attempt < 3) {
                                        throw new RuntimeException("Temporary error");
                                    }
                                    return new double[] {0.1, 0.2};
                                })
                        .retryWhen(
                                reactor.util.retry.Retry.backoff(2, Duration.ofMillis(10))
                                        .filter(error -> error instanceof RuntimeException));

        Mono<double[]> result =
                EmbeddingUtils.applyTimeoutAndRetry(
                        retryableMono, config, "test-model", "test-provider", log);

        StepVerifier.create(result)
                .assertNext(
                        embedding -> {
                            assertEquals(2, embedding.length);
                            assertEquals(0.1, embedding[0], 0.001);
                            assertEquals(0.2, embedding[1], 0.001);
                        })
                .verifyComplete();
        assertEquals(3, attemptCount.get(), "Should have retried 2 times (3 total attempts)");
    }

    @Test
    @DisplayName("Should not retry when maxAttempts is 1")
    void testNoRetryWhenMaxAttemptsIsOne() {
        AtomicInteger attemptCount = new AtomicInteger(0);
        ExecutionConfig config = ExecutionConfig.builder().maxAttempts(1).build();

        Mono<double[]> failingMono =
                Mono.fromCallable(
                        () -> {
                            attemptCount.incrementAndGet();
                            throw new RuntimeException("Error");
                        });

        Mono<double[]> result =
                EmbeddingUtils.applyTimeoutAndRetry(
                        failingMono, config, "test-model", "test-provider", log);

        StepVerifier.create(result).expectError(RuntimeException.class).verify();
        assertEquals(1, attemptCount.get(), "Should not retry when maxAttempts is 1");
    }

    @Test
    @DisplayName("Should apply timeout and retry to batch embeddings")
    void testBatchTimeoutAndRetry() {
        ExecutionConfig config =
                ExecutionConfig.builder()
                        .timeout(Duration.ofSeconds(1))
                        .maxAttempts(2)
                        .initialBackoff(Duration.ofMillis(10))
                        .build();

        List<double[]> testEmbeddings = List.of(new double[] {0.1, 0.2}, new double[] {0.3, 0.4});
        Mono<List<double[]>> batchMono = Mono.just(testEmbeddings);

        Mono<List<double[]>> result =
                EmbeddingUtils.applyTimeoutAndRetryBatch(
                        batchMono, config, "test-model", "test-provider", log);

        StepVerifier.create(result).expectNext(testEmbeddings).verifyComplete();
    }

    @Test
    @DisplayName("Should return MODEL_DEFAULTS when config is null")
    void testEnsureDefaultExecutionConfigWithNull() {
        ExecutionConfig result = EmbeddingUtils.ensureDefaultExecutionConfig(null);

        assertNotNull(result);
        assertEquals(ExecutionConfig.MODEL_DEFAULTS, result);
    }

    @Test
    @DisplayName("Should return original config when provided")
    void testEnsureDefaultExecutionConfigWithConfig() {
        ExecutionConfig customConfig =
                ExecutionConfig.builder().timeout(Duration.ofSeconds(30)).maxAttempts(5).build();

        ExecutionConfig result = EmbeddingUtils.ensureDefaultExecutionConfig(customConfig);

        assertSame(customConfig, result);
    }

    @Test
    @DisplayName("Should handle batch timeout correctly")
    void testBatchTimeout() {
        ExecutionConfig config = ExecutionConfig.builder().timeout(Duration.ofMillis(100)).build();

        // Create a Mono that delays longer than timeout
        Mono<List<double[]>> slowBatchMono =
                Mono.<List<double[]>>just(new ArrayList<>()).delayElement(Duration.ofMillis(200));

        Mono<List<double[]>> result =
                EmbeddingUtils.applyTimeoutAndRetryBatch(
                        slowBatchMono, config, "test-model", "test-provider", log);

        StepVerifier.create(result).expectError(EmbeddingException.class).verify();
    }
}
