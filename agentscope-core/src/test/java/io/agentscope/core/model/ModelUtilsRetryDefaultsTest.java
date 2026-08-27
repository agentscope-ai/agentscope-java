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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.message.TextBlock;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Tests for the default retry policy of {@link ModelUtils#applyTimeoutAndRetry} when no {@code
 * retryOn} predicate is configured: the default must follow {@link ExecutionConfig#RETRYABLE_ERRORS}
 * instead of retrying every error.
 */
@DisplayName("ModelUtils default retry policy when retryOn is unset")
class ModelUtilsRetryDefaultsTest {

    @Test
    @DisplayName("Should not retry non-retryable (401) errors when retryOn is unset")
    void shouldNotRetryAuthErrorsByDefault() {
        AtomicInteger attempts = new AtomicInteger();
        Flux<ChatResponse> flux =
                Flux.defer(
                        () -> {
                            attempts.incrementAndGet();
                            return Flux.error(new TestModelHttpException(401));
                        });

        StepVerifier.create(
                        ModelUtils.applyTimeoutAndRetry(
                                flux, optionsWithoutRetryOn(), null, "test-model", "test"))
                .expectError(TestModelHttpException.class)
                .verify();

        assertEquals(1, attempts.get(), "auth errors must fail fast without retries");
    }

    @Test
    @DisplayName("Should not retry unknown errors when retryOn is unset")
    void shouldNotRetryUnknownErrorsByDefault() {
        AtomicInteger attempts = new AtomicInteger();
        Flux<ChatResponse> flux =
                Flux.defer(
                        () -> {
                            attempts.incrementAndGet();
                            return Flux.error(new IllegalArgumentException("bad request"));
                        });

        StepVerifier.create(
                        ModelUtils.applyTimeoutAndRetry(
                                flux, optionsWithoutRetryOn(), null, "test-model", "test"))
                .expectError(IllegalArgumentException.class)
                .verify();

        assertEquals(1, attempts.get(), "unknown errors must fail fast without retries");
    }

    @Test
    @DisplayName("Should retry retryable (network) errors when retryOn is unset")
    void shouldRetryNetworkErrorsByDefault() {
        AtomicInteger attempts = new AtomicInteger();
        Flux<ChatResponse> flux =
                Flux.defer(
                        () -> {
                            return attempts.incrementAndGet() == 1
                                    ? Flux.error(new IOException("connection reset"))
                                    : Flux.just(mockResponse());
                        });

        StepVerifier.create(
                        ModelUtils.applyTimeoutAndRetry(
                                flux, optionsWithoutRetryOn(), null, "test-model", "test"))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(2, attempts.get(), "network errors must be retried by default");
    }

    private static GenerateOptions optionsWithoutRetryOn() {
        ExecutionConfig config =
                ExecutionConfig.builder()
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofMillis(10))
                        .maxBackoff(Duration.ofMillis(50))
                        .build();
        return GenerateOptions.builder().executionConfig(config).build();
    }

    private static ChatResponse mockResponse() {
        return new ChatResponse(
                "test-id",
                List.of(TextBlock.builder().text("test response").build()),
                null,
                null,
                null);
    }

    /** Minimal model exception carrying an HTTP status code for retry classification. */
    private static final class TestModelHttpException extends RuntimeException
            implements ModelHttpException {

        private final int statusCode;

        TestModelHttpException(int statusCode) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        @Override
        public Integer getStatusCode() {
            return statusCode;
        }
    }
}
