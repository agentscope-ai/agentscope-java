/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.util.retry.RetryBackoffSpec;

/**
 * Unit tests for {@link RetrySpecs}.
 *
 * <p>These tests lock in the fix for the "dead field" bug where
 * {@link ExecutionConfig#getBackoffMultiplier()} was never applied. They assert directly on the
 * public final fields of the returned {@link RetryBackoffSpec} so the checks are deterministic
 * (no real-time backoff sleeps, no jitter randomness).
 */
@DisplayName("RetrySpecs: backoff multiplier is honoured")
class RetrySpecsTest {

    @Test
    @DisplayName("Defaults to multiplier 2.0 and 1s/10s backoffs when knobs are null")
    void shouldApplyDefaultsWhenKnobsAreNull() {
        ExecutionConfig config = ExecutionConfig.builder().maxAttempts(3).build();

        RetryBackoffSpec spec = RetrySpecs.build(config);

        // multiplier was a dead field before the fix; it must now default to 2.0
        assertEquals(2.0d, spec.multiplier, 0.0d, "default multiplier should be 2.0");
        assertEquals(Duration.ofSeconds(1), spec.minBackoff, "default minBackoff should be 1s");
        assertEquals(Duration.ofSeconds(10), spec.maxBackoff, "default maxBackoff should be 10s");
        assertEquals(2L, spec.maxAttempts, "maxAttempts(3) -> 2 retry attempts");
        assertEquals(0.5d, spec.jitterFactor, 0.0d, "jitter factor should be 0.5");
        // default filter retries everything
        assertTrue(
                spec.errorFilter.test(new RuntimeException("any")),
                "default errorFilter should retry all errors");
    }

    @Test
    @DisplayName("Propagates a custom backoffMultiplier (1.5) instead of dropping it")
    void shouldHonourCustomMultiplier() {
        ExecutionConfig config =
                ExecutionConfig.builder()
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofMillis(100))
                        .backoffMultiplier(1.5d)
                        .build();

        RetryBackoffSpec spec = RetrySpecs.build(config);

        // Before the fix this assertion would have failed (Reactor default 2.0 was always used).
        assertEquals(1.5d, spec.multiplier, 0.0d, "custom multiplier must be propagated");
        assertEquals(Duration.ofMillis(100), spec.minBackoff);
    }

    @Test
    @DisplayName("Propagates a larger custom backoffMultiplier (3.0)")
    void shouldHonourLargerMultiplier() {
        ExecutionConfig config =
                ExecutionConfig.builder()
                        .maxAttempts(4)
                        .maxBackoff(Duration.ofSeconds(60))
                        .backoffMultiplier(3.0d)
                        .build();

        RetryBackoffSpec spec = RetrySpecs.build(config);

        assertEquals(3.0d, spec.multiplier, 0.0d);
        assertEquals(3L, spec.maxAttempts, "maxAttempts(4) -> 3 retry attempts");
        assertEquals(Duration.ofSeconds(60), spec.maxBackoff);
    }

    @Test
    @DisplayName("Propagates custom backoffs, maxAttempts and retryOn predicate")
    void shouldHonourAllConfiguredKnobs() {
        ExecutionConfig config =
                ExecutionConfig.builder()
                        .maxAttempts(5)
                        .initialBackoff(Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(30))
                        .backoffMultiplier(2.0d)
                        .retryOn(error -> error instanceof ModelException)
                        .build();

        RetryBackoffSpec spec = RetrySpecs.build(config);

        assertEquals(2.0d, spec.multiplier, 0.0d);
        assertEquals(Duration.ofSeconds(2), spec.minBackoff);
        assertEquals(Duration.ofSeconds(30), spec.maxBackoff);
        assertEquals(4L, spec.maxAttempts, "maxAttempts(5) -> 4 retry attempts");
        assertTrue(
                spec.errorFilter.test(new ModelException("retryable", "model", "provider")),
                "configured retryOn should accept ModelException");
        assertFalse(
                spec.errorFilter.test(new IllegalArgumentException("non-retryable")),
                "configured retryOn should reject other errors");
    }

    @Test
    @DisplayName("Round-trips ExecutionConfig.MODEL_DEFAULTS through the builder")
    void shouldRoundTripModelDefaults() {
        RetryBackoffSpec spec = RetrySpecs.build(ExecutionConfig.MODEL_DEFAULTS);

        assertEquals(2.0d, spec.multiplier, 0.0d, "MODEL_DEFAULTS multiplier is 2.0");
        assertEquals(Duration.ofSeconds(2), spec.minBackoff);
        assertEquals(Duration.ofSeconds(30), spec.maxBackoff);
        // MODEL_DEFAULTS.maxAttempts == 3 -> 2 retry attempts
        assertEquals(2L, spec.maxAttempts);
    }
}
