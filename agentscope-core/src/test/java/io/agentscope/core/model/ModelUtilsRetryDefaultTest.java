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

import io.agentscope.core.model.transport.HttpTransportException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Verifies the default {@code retryOn} semantics of
 * {@link ModelUtils#applyTimeoutAndRetry} when no explicit predicate is configured.
 *
 * <p>Regression guard: when {@link ExecutionConfig#getRetryOn()} is {@code null} the retry
 * predicate must default to {@link ExecutionConfig#RETRYABLE_ERRORS} (retry 429/5xx/timeouts/
 * network errors only) instead of retrying every error — retrying auth (401/403) or request-side
 * (400/422) failures is guaranteed to fail again.
 */
@Tag("unit")
@DisplayName("ModelUtils Default RetryOn Semantics Tests")
class ModelUtilsRetryDefaultTest {

    private static final String MODEL = "test-model";
    private static final String PROVIDER = "test-provider";

    private GenerateOptions optionsWithMaxAttemptsButNoRetryOn() {
        return GenerateOptions.builder()
                .executionConfig(
                        ExecutionConfig.builder()
                                .maxAttempts(3)
                                .initialBackoff(Duration.ofMillis(5))
                                .maxBackoff(Duration.ofMillis(20))
                                .build())
                .build();
    }

    @Test
    @DisplayName("Default retryOn must not retry auth errors (401)")
    void defaultRetryOnDoesNotRetryAuthErrors() {
        AtomicInteger attempts = new AtomicInteger();

        Flux<ChatResponse> source =
                Flux.defer(
                        () -> {
                            attempts.incrementAndGet();
                            return Flux.error(
                                    new HttpTransportException(
                                            "unauthorized",
                                            401,
                                            "{\"error\":\"invalid_api_key\"}"));
                        });

        StepVerifier.create(
                        ModelUtils.applyTimeoutAndRetry(
                                source,
                                optionsWithMaxAttemptsButNoRetryOn(),
                                null,
                                MODEL,
                                PROVIDER))
                .expectError()
                .verify(Duration.ofSeconds(5));

        assertEquals(1, attempts.get(), "401 must not be retried by the default predicate");
    }

    @Test
    @DisplayName("Default retryOn must retry rate-limit errors (429)")
    void defaultRetryOnRetriesRateLimitErrors() {
        AtomicInteger attempts = new AtomicInteger();

        Flux<ChatResponse> source =
                Flux.defer(
                        () -> {
                            attempts.incrementAndGet();
                            return Flux.error(
                                    new HttpTransportException(
                                            "rate limited",
                                            429,
                                            "{\"error\":\"rate_limit_exceeded\"}"));
                        });

        StepVerifier.create(
                        ModelUtils.applyTimeoutAndRetry(
                                source,
                                optionsWithMaxAttemptsButNoRetryOn(),
                                null,
                                MODEL,
                                PROVIDER))
                .expectError()
                .verify(Duration.ofSeconds(10));

        assertEquals(3, attempts.get(), "429 must be retried up to maxAttempts");
    }

    @Test
    @DisplayName("Default retryOn must not retry request-side errors (400)")
    void defaultRetryOnDoesNotRetryBadRequestErrors() {
        AtomicInteger attempts = new AtomicInteger();

        Flux<ChatResponse> source =
                Flux.defer(
                        () -> {
                            attempts.incrementAndGet();
                            return Flux.error(
                                    new HttpTransportException(
                                            "bad request", 400, "{\"error\":\"invalid_request\"}"));
                        });

        StepVerifier.create(
                        ModelUtils.applyTimeoutAndRetry(
                                source,
                                optionsWithMaxAttemptsButNoRetryOn(),
                                null,
                                MODEL,
                                PROVIDER))
                .expectError()
                .verify(Duration.ofSeconds(5));

        assertEquals(1, attempts.get(), "400 must not be retried by the default predicate");
    }

    @Test
    @DisplayName("Default retryOn must retry timeout errors")
    void defaultRetryOnRetriesTimeouts() {
        AtomicInteger attempts = new AtomicInteger();

        Flux<ChatResponse> source =
                Flux.defer(
                        () -> {
                            attempts.incrementAndGet();
                            return Flux.error(new TimeoutException("read timed out"));
                        });

        StepVerifier.create(
                        ModelUtils.applyTimeoutAndRetry(
                                source,
                                optionsWithMaxAttemptsButNoRetryOn(),
                                null,
                                MODEL,
                                PROVIDER))
                .expectError()
                .verify(Duration.ofSeconds(10));

        assertEquals(3, attempts.get(), "timeouts must be retried up to maxAttempts");
    }

    @Test
    @DisplayName("Explicit retryOn must still be respected when configured")
    void explicitRetryOnIsRespected() {
        AtomicInteger attempts = new AtomicInteger();

        Flux<ChatResponse> source =
                Flux.defer(
                        () -> {
                            attempts.incrementAndGet();
                            return Flux.error(
                                    new HttpTransportException(
                                            "unauthorized", 401, "{\"error\":\"invalid_key\"}"));
                        });

        GenerateOptions options =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .maxAttempts(3)
                                        .initialBackoff(Duration.ofMillis(5))
                                        .maxBackoff(Duration.ofMillis(20))
                                        .retryOn(error -> true) // explicit: retry everything
                                        .build())
                        .build();

        StepVerifier.create(ModelUtils.applyTimeoutAndRetry(source, options, null, MODEL, PROVIDER))
                .expectError()
                .verify(Duration.ofSeconds(10));

        assertEquals(3, attempts.get(), "an explicit retryOn=true must retry 401");
    }
}
