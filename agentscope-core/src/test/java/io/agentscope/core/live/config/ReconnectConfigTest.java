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
package io.agentscope.core.live.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReconnectConfig Tests")
class ReconnectConfigTest {

    @Test
    @DisplayName("Should create default config")
    void shouldCreateDefaultConfig() {
        ReconnectConfig config = ReconnectConfig.defaults();

        assertEquals(3, config.getMaxAttempts());
        assertEquals(Duration.ofMillis(500), config.getInitialDelay());
        assertEquals(Duration.ofSeconds(10), config.getMaxDelay());
        assertEquals(2.0, config.getBackoffMultiplier());
        assertEquals(0.2, config.getJitterFactor());
    }

    @Test
    @DisplayName("Should calculate delay with exponential backoff")
    void shouldCalculateDelayWithExponentialBackoff() {
        ReconnectConfig config =
                ReconnectConfig.builder()
                        .initialDelay(Duration.ofMillis(100))
                        .backoffMultiplier(2.0)
                        .jitterFactor(0.0) // No jitter for predictable test
                        .build();

        assertEquals(100, config.getDelayForAttempt(1).toMillis());
        assertEquals(200, config.getDelayForAttempt(2).toMillis());
        assertEquals(400, config.getDelayForAttempt(3).toMillis());
    }

    @Test
    @DisplayName("Should cap delay at maxDelay")
    void shouldCapDelayAtMaxDelay() {
        ReconnectConfig config =
                ReconnectConfig.builder()
                        .initialDelay(Duration.ofSeconds(1))
                        .maxDelay(Duration.ofSeconds(5))
                        .backoffMultiplier(10.0)
                        .jitterFactor(0.0)
                        .build();

        // 1 * 10^2 = 100 seconds, but capped at 5 seconds
        assertEquals(5000, config.getDelayForAttempt(3).toMillis());
    }

    @Test
    @DisplayName("Should apply jitter to delay")
    void shouldApplyJitterToDelay() {
        ReconnectConfig config =
                ReconnectConfig.builder()
                        .initialDelay(Duration.ofMillis(1000))
                        .backoffMultiplier(1.0)
                        .jitterFactor(0.2)
                        .build();

        // With jitter, delay should be within ±20% of base delay
        long delay = config.getDelayForAttempt(1).toMillis();
        assertTrue(delay >= 800 && delay <= 1200, "Delay should be within jitter range");
    }

    @Test
    @DisplayName("Should build custom config")
    void shouldBuildCustomConfig() {
        ReconnectConfig config =
                ReconnectConfig.builder()
                        .maxAttempts(5)
                        .initialDelay(Duration.ofSeconds(2))
                        .maxDelay(Duration.ofSeconds(30))
                        .backoffMultiplier(3.0)
                        .jitterFactor(0.1)
                        .build();

        assertEquals(5, config.getMaxAttempts());
        assertEquals(Duration.ofSeconds(2), config.getInitialDelay());
        assertEquals(Duration.ofSeconds(30), config.getMaxDelay());
        assertEquals(3.0, config.getBackoffMultiplier());
        assertEquals(0.1, config.getJitterFactor());
    }

    @Test
    @DisplayName("Should implement equals and hashCode")
    void shouldImplementEqualsAndHashCode() {
        ReconnectConfig config1 =
                ReconnectConfig.builder()
                        .maxAttempts(3)
                        .initialDelay(Duration.ofMillis(500))
                        .build();
        ReconnectConfig config2 =
                ReconnectConfig.builder()
                        .maxAttempts(3)
                        .initialDelay(Duration.ofMillis(500))
                        .build();
        ReconnectConfig config3 =
                ReconnectConfig.builder()
                        .maxAttempts(5)
                        .initialDelay(Duration.ofMillis(500))
                        .build();

        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());
        assertNotEquals(config1, config3);
    }
}
