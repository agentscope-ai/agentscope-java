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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TimeoutConfig and its Builder.
 */
@DisplayName("TimeoutConfig Tests")
class TimeoutConfigTest {

    @Test
    @DisplayName("Should build TimeoutConfig with default values (all null)")
    void testBuilderDefaults() {
        TimeoutConfig config = TimeoutConfig.builder().build();

        assertNotNull(config);
        assertNull(config.getAgentCallTimeout());
        assertNull(config.getModelRequestTimeout());
        assertNull(config.getToolExecutionTimeout());
    }

    @Test
    @DisplayName("Should build TimeoutConfig with all custom parameters")
    void testBuilderAllParameters() {
        TimeoutConfig config =
                TimeoutConfig.builder()
                        .agentCallTimeout(Duration.ofMinutes(5))
                        .modelRequestTimeout(Duration.ofMinutes(2))
                        .toolExecutionTimeout(Duration.ofSeconds(30))
                        .build();

        assertNotNull(config);
        assertEquals(Duration.ofMinutes(5), config.getAgentCallTimeout());
        assertEquals(Duration.ofMinutes(2), config.getModelRequestTimeout());
        assertEquals(Duration.ofSeconds(30), config.getToolExecutionTimeout());
    }

    @Test
    @DisplayName("Should build TimeoutConfig with partial parameters")
    void testBuilderPartialParameters() {
        // Only set agent call timeout
        TimeoutConfig config1 =
                TimeoutConfig.builder().agentCallTimeout(Duration.ofMinutes(10)).build();

        assertNotNull(config1);
        assertEquals(Duration.ofMinutes(10), config1.getAgentCallTimeout());
        assertNull(config1.getModelRequestTimeout());
        assertNull(config1.getToolExecutionTimeout());

        // Only set model request timeout
        TimeoutConfig config2 =
                TimeoutConfig.builder().modelRequestTimeout(Duration.ofSeconds(90)).build();

        assertNotNull(config2);
        assertNull(config2.getAgentCallTimeout());
        assertEquals(Duration.ofSeconds(90), config2.getModelRequestTimeout());
        assertNull(config2.getToolExecutionTimeout());

        // Set two timeouts
        TimeoutConfig config3 =
                TimeoutConfig.builder()
                        .agentCallTimeout(Duration.ofMinutes(3))
                        .toolExecutionTimeout(Duration.ofSeconds(15))
                        .build();

        assertEquals(Duration.ofMinutes(3), config3.getAgentCallTimeout());
        assertNull(config3.getModelRequestTimeout());
        assertEquals(Duration.ofSeconds(15), config3.getToolExecutionTimeout());
    }

    @Test
    @DisplayName("Should support builder method chaining")
    void testBuilderChaining() {
        TimeoutConfig.Builder builder = TimeoutConfig.builder();

        TimeoutConfig config =
                builder.agentCallTimeout(Duration.ofMinutes(10))
                        .modelRequestTimeout(Duration.ofMinutes(3))
                        .toolExecutionTimeout(Duration.ofSeconds(60))
                        .build();

        assertNotNull(config);
        assertEquals(Duration.ofMinutes(10), config.getAgentCallTimeout());
        assertEquals(Duration.ofMinutes(3), config.getModelRequestTimeout());
        assertEquals(Duration.ofSeconds(60), config.getToolExecutionTimeout());
    }

    @Test
    @DisplayName("Should handle edge case timeout values")
    void testEdgeCaseTimeoutValues() {
        TimeoutConfig config =
                TimeoutConfig.builder()
                        .agentCallTimeout(Duration.ZERO)
                        .modelRequestTimeout(Duration.ofDays(1))
                        .toolExecutionTimeout(Duration.ofMillis(100))
                        .build();

        assertEquals(Duration.ZERO, config.getAgentCallTimeout());
        assertEquals(Duration.ofDays(1), config.getModelRequestTimeout());
        assertEquals(Duration.ofMillis(100), config.getToolExecutionTimeout());
    }

    @Test
    @DisplayName("Should allow null timeout values explicitly")
    void testExplicitNullTimeouts() {
        TimeoutConfig config =
                TimeoutConfig.builder()
                        .agentCallTimeout(null)
                        .modelRequestTimeout(null)
                        .toolExecutionTimeout(null)
                        .build();

        assertNotNull(config);
        assertNull(config.getAgentCallTimeout());
        assertNull(config.getModelRequestTimeout());
        assertNull(config.getToolExecutionTimeout());
    }

    @Test
    @DisplayName("Should throw exception when agentCallTimeout is negative")
    void testNegativeAgentCallTimeout() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> TimeoutConfig.builder().agentCallTimeout(Duration.ofSeconds(-1)));

        assertTrue(exception.getMessage().contains("agentCallTimeout must not be negative"));
    }

    @Test
    @DisplayName("Should throw exception when modelRequestTimeout is negative")
    void testNegativeModelRequestTimeout() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> TimeoutConfig.builder().modelRequestTimeout(Duration.ofSeconds(-5)));

        assertTrue(exception.getMessage().contains("modelRequestTimeout must not be negative"));
    }

    @Test
    @DisplayName("Should throw exception when toolExecutionTimeout is negative")
    void testNegativeToolExecutionTimeout() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                TimeoutConfig.builder()
                                        .toolExecutionTimeout(Duration.ofMillis(-100)));

        assertTrue(exception.getMessage().contains("toolExecutionTimeout must not be negative"));
    }

    @Test
    @DisplayName("Should create independent instances from same builder")
    void testBuilderProducesIndependentInstances() {
        TimeoutConfig.Builder builder =
                TimeoutConfig.builder().agentCallTimeout(Duration.ofMinutes(5));

        TimeoutConfig config1 = builder.build();
        TimeoutConfig config2 = builder.agentCallTimeout(Duration.ofMinutes(10)).build();

        // First config should not be affected by subsequent builder changes
        assertEquals(Duration.ofMinutes(5), config1.getAgentCallTimeout());
        assertEquals(Duration.ofMinutes(10), config2.getAgentCallTimeout());
    }

    @Test
    @DisplayName("Should support realistic production timeout configuration")
    void testRealisticProductionConfig() {
        TimeoutConfig config =
                TimeoutConfig.builder()
                        .agentCallTimeout(Duration.ofMinutes(5)) // 5 minutes for whole agent
                        .modelRequestTimeout(Duration.ofMinutes(2)) // 2 minutes per model call
                        .toolExecutionTimeout(Duration.ofSeconds(30)) // 30 seconds per tool
                        .build();

        assertEquals(Duration.ofMinutes(5), config.getAgentCallTimeout());
        assertEquals(Duration.ofMinutes(2), config.getModelRequestTimeout());
        assertEquals(Duration.ofSeconds(30), config.getToolExecutionTimeout());
    }

    @Test
    @DisplayName("Should support overriding timeout values in builder")
    void testOverridingTimeoutValues() {
        TimeoutConfig.Builder builder = TimeoutConfig.builder();

        // Set initial values
        builder.agentCallTimeout(Duration.ofMinutes(1)).modelRequestTimeout(Duration.ofSeconds(30));

        // Override with new values
        builder.agentCallTimeout(Duration.ofMinutes(2)).modelRequestTimeout(Duration.ofSeconds(60));

        TimeoutConfig config = builder.build();

        // Should use the overridden values
        assertEquals(Duration.ofMinutes(2), config.getAgentCallTimeout());
        assertEquals(Duration.ofSeconds(60), config.getModelRequestTimeout());
    }
}
