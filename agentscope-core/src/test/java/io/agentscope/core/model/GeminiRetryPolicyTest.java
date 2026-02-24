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

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@Tag("unit")
@DisplayName("GeminiRetryPolicy Unit Tests")
class GeminiRetryPolicyTest {

    @Test
    @DisplayName("Should retry on retryable Gemini API status codes")
    void testRetryableHttpStatus() {
        GeminiRetryPolicy policy =
                new GeminiRetryPolicy("gemini-2.5-flash", LoggerFactory.getLogger(getClass()));
        AtomicInteger attempts = new AtomicInteger(0);

        Flux<String> flux =
                Flux.defer(
                                () -> {
                                    int attempt = attempts.incrementAndGet();
                                    if (attempt == 1) {
                                        return Flux.<String>error(
                                                new GeminiApiException(429, "rate limit"));
                                    }
                                    return Flux.just("ok");
                                })
                        .retryWhen(policy.build());

        StepVerifier.create(flux).expectNext("ok").verifyComplete();
        assertEquals(2, attempts.get());
    }

    @Test
    @DisplayName("Should not retry on non-retryable Gemini API status codes")
    void testNonRetryableHttpStatus() {
        GeminiRetryPolicy policy =
                new GeminiRetryPolicy("gemini-2.5-flash", LoggerFactory.getLogger(getClass()));
        AtomicInteger attempts = new AtomicInteger(0);

        Flux<String> flux =
                Flux.defer(
                                () -> {
                                    attempts.incrementAndGet();
                                    return Flux.<String>error(
                                            new GeminiApiException(400, "bad request"));
                                })
                        .retryWhen(policy.build());

        StepVerifier.create(flux)
                .expectErrorMatches(
                        throwable ->
                                throwable instanceof GeminiApiException
                                        && ((GeminiApiException) throwable).getStatusCode() == 400)
                .verify();
        assertEquals(1, attempts.get());
    }

    @Test
    @DisplayName("Should retry Gemini 3 empty content model errors")
    void testGemini3EmptyContentRetry() {
        GeminiRetryPolicy policy =
                new GeminiRetryPolicy(
                        "gemini-3-flash-preview", LoggerFactory.getLogger(getClass()));
        AtomicInteger attempts = new AtomicInteger(0);

        Flux<String> flux =
                Flux.defer(
                                () -> {
                                    int attempt = attempts.incrementAndGet();
                                    if (attempt == 1) {
                                        return Flux.<String>error(
                                                new ModelException(
                                                        "Gemini returned empty content"
                                                                + " (finishReason:"
                                                                + " MALFORMED_FUNCTION_CALL)"));
                                    }
                                    return Flux.just("recovered");
                                })
                        .retryWhen(policy.build());

        StepVerifier.create(flux).expectNext("recovered").verifyComplete();
        assertEquals(2, attempts.get());
    }
}
