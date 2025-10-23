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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for GenerateOptions and its Builder.
 */
@DisplayName("GenerateOptions Tests")
class GenerateOptionsTest {

    @Test
    @DisplayName("Should build GenerateOptions with all parameters using builder")
    void testBuilderAllParameters() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.8)
                        .topP(0.95)
                        .maxTokens(4096)
                        .frequencyPenalty(0.3)
                        .presencePenalty(0.4)
                        .build();

        assertNotNull(options);
        assertEquals(0.8, options.getTemperature());
        assertEquals(0.95, options.getTopP());
        assertEquals(4096, options.getMaxTokens());
        assertEquals(0.3, options.getFrequencyPenalty());
        assertEquals(0.4, options.getPresencePenalty());
    }

    @Test
    @DisplayName("Should build GenerateOptions with partial parameters")
    void testBuilderPartialParameters() {
        GenerateOptions options =
                GenerateOptions.builder().temperature(0.5).maxTokens(1024).build();

        assertNotNull(options);
        assertEquals(0.5, options.getTemperature());
        assertEquals(1024, options.getMaxTokens());
        assertNull(options.getTopP());
        assertNull(options.getFrequencyPenalty());
        assertNull(options.getPresencePenalty());
    }

    @Test
    @DisplayName("Should build GenerateOptions with no parameters")
    void testBuilderNoParameters() {
        GenerateOptions options = GenerateOptions.builder().build();

        assertNotNull(options);
        assertNull(options.getTemperature());
        assertNull(options.getTopP());
        assertNull(options.getMaxTokens());
        assertNull(options.getFrequencyPenalty());
        assertNull(options.getPresencePenalty());
    }

    @Test
    @DisplayName("Should support builder method chaining")
    void testBuilderChaining() {
        GenerateOptions.Builder builder = GenerateOptions.builder();

        GenerateOptions options =
                builder.temperature(0.7)
                        .topP(0.9)
                        .maxTokens(2048)
                        .frequencyPenalty(0.2)
                        .presencePenalty(0.3)
                        .build();

        assertNotNull(options);
        assertEquals(0.7, options.getTemperature());
    }

    @Test
    @DisplayName("Should handle edge case values")
    void testEdgeCaseValues() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.0)
                        .topP(1.0)
                        .maxTokens(1)
                        .frequencyPenalty(-2.0)
                        .presencePenalty(2.0)
                        .build();

        assertEquals(0.0, options.getTemperature());
        assertEquals(1.0, options.getTopP());
        assertEquals(1, options.getMaxTokens());
        assertEquals(-2.0, options.getFrequencyPenalty());
        assertEquals(2.0, options.getPresencePenalty());
    }

    @Test
    @DisplayName("Should handle null values in builder")
    void testBuilderNullValues() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(null)
                        .topP(null)
                        .maxTokens(null)
                        .frequencyPenalty(null)
                        .presencePenalty(null)
                        .build();

        assertNotNull(options);
        assertNull(options.getTemperature());
        assertNull(options.getTopP());
        assertNull(options.getMaxTokens());
        assertNull(options.getFrequencyPenalty());
        assertNull(options.getPresencePenalty());
    }
}
