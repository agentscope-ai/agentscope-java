/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for RetryConfig and its Builder.
 */
@DisplayName("RetryConfig Tests")
class RetryConfigTest {

    @Test
    @DisplayName("Should build RetryConfig with default values")
    void testBuilderDefaults() {
        RetryConfig config = RetryConfig.builder().build();

        assertNotNull(config);
        assertEquals(3, config.getMaxAttempts());
        assertEquals(Duration.ofSeconds(1), config.getInitialBackoff());
        assertEquals(Duration.ofSeconds(10), config.getMaxBackoff());
        assertEquals(2.0, config.getBackoffMultiplier());
        assertNotNull(config.getRetryOn());
        // Default retryOn accepts all throwables
        assertTrue(config.getRetryOn().test(new RuntimeException("test")));
    }

    @Test
    @DisplayName("Should build RetryConfig with all custom parameters")
    void testBuilderAllParameters() {
        RetryConfig config =
                RetryConfig.builder()
                        .maxAttempts(5)
                        .initialBackoff(Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(30))
                        .backoffMultiplier(1.5)
                        .retryOn(error -> error instanceof ModelException)
                        .build();

        assertNotNull(config);
        assertEquals(5, config.getMaxAttempts());
        assertEquals(Duration.ofSeconds(2), config.getInitialBackoff());
        assertEquals(Duration.ofSeconds(30), config.getMaxBackoff());
        assertEquals(1.5, config.getBackoffMultiplier());
        assertNotNull(config.getRetryOn());

        // Should only retry on ModelException
        assertTrue(
                config.getRetryOn()
                        .test(new ModelException("test error", "model-1", "provider-1")));
        assertFalse(config.getRetryOn().test(new RuntimeException("other error")));
    }

    @Test
    @DisplayName("Should build RetryConfig with partial parameters using defaults")
    void testBuilderPartialParameters() {
        RetryConfig config =
                RetryConfig.builder()
                        .maxAttempts(10)
                        .initialBackoff(Duration.ofMillis(500))
                        .build();

        assertNotNull(config);
        assertEquals(10, config.getMaxAttempts());
        assertEquals(Duration.ofMillis(500), config.getInitialBackoff());
        // Other fields should use defaults
        assertEquals(Duration.ofSeconds(10), config.getMaxBackoff());
        assertEquals(2.0, config.getBackoffMultiplier());
    }

    @Test
    @DisplayName("Should support builder method chaining")
    void testBuilderChaining() {
        RetryConfig.Builder builder = RetryConfig.builder();

        RetryConfig config =
                builder.maxAttempts(4)
                        .initialBackoff(Duration.ofSeconds(3))
                        .maxBackoff(Duration.ofSeconds(20))
                        .backoffMultiplier(3.0)
                        .build();

        assertNotNull(config);
        assertEquals(4, config.getMaxAttempts());
        assertEquals(Duration.ofSeconds(3), config.getInitialBackoff());
    }

    @Test
    @DisplayName("Should handle edge case values for maxAttempts")
    void testEdgeCaseMaxAttempts() {
        // Minimum valid value
        RetryConfig config1 = RetryConfig.builder().maxAttempts(1).build();
        assertEquals(1, config1.getMaxAttempts());

        // Large value
        RetryConfig config2 = RetryConfig.builder().maxAttempts(Integer.MAX_VALUE).build();
        assertEquals(Integer.MAX_VALUE, config2.getMaxAttempts());
    }

    @Test
    @DisplayName("Should handle edge case values for backoff durations")
    void testEdgeCaseBackoffDurations() {
        RetryConfig config =
                RetryConfig.builder()
                        .initialBackoff(Duration.ZERO)
                        .maxBackoff(Duration.ofDays(1))
                        .build();

        assertEquals(Duration.ZERO, config.getInitialBackoff());
        assertEquals(Duration.ofDays(1), config.getMaxBackoff());
    }

    @Test
    @DisplayName("Should handle edge case values for backoffMultiplier")
    void testEdgeCaseBackoffMultiplier() {
        // Very small positive value
        RetryConfig config1 = RetryConfig.builder().backoffMultiplier(0.1).build();
        assertEquals(0.1, config1.getBackoffMultiplier());

        // Large value
        RetryConfig config2 = RetryConfig.builder().backoffMultiplier(100.0).build();
        assertEquals(100.0, config2.getBackoffMultiplier());
    }

    @Test
    @DisplayName("Should throw exception when maxAttempts is less than 1")
    void testInvalidMaxAttempts() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class, () -> RetryConfig.builder().maxAttempts(0));

        assertTrue(exception.getMessage().contains("maxAttempts must be at least 1"));

        // Negative value
        IllegalArgumentException exception2 =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> RetryConfig.builder().maxAttempts(-1));

        assertTrue(exception2.getMessage().contains("maxAttempts must be at least 1"));
    }

    @Test
    @DisplayName("Should throw exception when initialBackoff is null")
    void testNullInitialBackoff() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> RetryConfig.builder().initialBackoff(null));

        assertTrue(exception.getMessage().contains("initialBackoff must not be null"));
    }

    @Test
    @DisplayName("Should throw exception when initialBackoff is negative")
    void testNegativeInitialBackoff() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> RetryConfig.builder().initialBackoff(Duration.ofSeconds(-1)));

        assertTrue(exception.getMessage().contains("initialBackoff must not be negative"));
    }

    @Test
    @DisplayName("Should throw exception when maxBackoff is null")
    void testNullMaxBackoff() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> RetryConfig.builder().maxBackoff(null));

        assertTrue(exception.getMessage().contains("maxBackoff must not be null"));
    }

    @Test
    @DisplayName("Should throw exception when maxBackoff is negative")
    void testNegativeMaxBackoff() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> RetryConfig.builder().maxBackoff(Duration.ofSeconds(-1)));

        assertTrue(exception.getMessage().contains("maxBackoff must not be negative"));
    }

    @Test
    @DisplayName("Should throw exception when backoffMultiplier is zero or negative")
    void testInvalidBackoffMultiplier() {
        // Zero
        IllegalArgumentException exception1 =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> RetryConfig.builder().backoffMultiplier(0));

        assertTrue(exception1.getMessage().contains("backoffMultiplier must be positive"));

        // Negative
        IllegalArgumentException exception2 =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> RetryConfig.builder().backoffMultiplier(-1.5));

        assertTrue(exception2.getMessage().contains("backoffMultiplier must be positive"));
    }

    @Test
    @DisplayName("Should throw exception when retryOn predicate is null")
    void testNullRetryOnPredicate() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class, () -> RetryConfig.builder().retryOn(null));

        assertTrue(exception.getMessage().contains("retryOn predicate must not be null"));
    }

    @Test
    @DisplayName("Should support custom retryOn predicate for specific error types")
    void testCustomRetryOnPredicate() {
        // Only retry on errors containing "timeout" or "rate limit"
        RetryConfig config =
                RetryConfig.builder()
                        .retryOn(
                                error -> {
                                    String msg = error.getMessage();
                                    return msg != null
                                            && (msg.contains("timeout")
                                                    || msg.contains("rate limit"));
                                })
                        .build();

        assertTrue(config.getRetryOn().test(new RuntimeException("Connection timeout")));
        assertTrue(config.getRetryOn().test(new RuntimeException("API rate limit exceeded")));
        assertFalse(config.getRetryOn().test(new RuntimeException("Invalid request")));
        assertFalse(config.getRetryOn().test(new RuntimeException((String) null)));
    }

    @Test
    @DisplayName("Should create independent instances from same builder")
    void testBuilderProducesIndependentInstances() {
        RetryConfig.Builder builder = RetryConfig.builder().maxAttempts(5);

        RetryConfig config1 = builder.build();
        RetryConfig config2 = builder.maxAttempts(10).build();

        // First config should not be affected by subsequent builder changes
        assertEquals(5, config1.getMaxAttempts());
        assertEquals(10, config2.getMaxAttempts());
    }
}
