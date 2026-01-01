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
package io.agentscope.core.rag.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for RetrieveConfig.
 */
@Tag("unit")
@DisplayName("RetrieveConfig Unit Tests")
class RetrieveConfigTest {

    @Test
    @DisplayName("Should create RetrieveConfig with default values")
    void testDefaultValues() {
        RetrieveConfig config = RetrieveConfig.builder().build();

        assertEquals(5, config.getLimit());
        assertEquals(0.5, config.getScoreThreshold());
    }

    @Test
    @DisplayName("Should create RetrieveConfig with custom values")
    void testCustomValues() {
        RetrieveConfig config = RetrieveConfig.builder().limit(10).scoreThreshold(0.7).build();

        assertEquals(10, config.getLimit());
        assertEquals(0.7, config.getScoreThreshold());
    }

    @Test
    @DisplayName("Should throw exception when limit is zero")
    void testInvalidLimitZero() {
        assertThrows(
                IllegalArgumentException.class, () -> RetrieveConfig.builder().limit(0).build());
    }

    @Test
    @DisplayName("Should throw exception when limit is negative")
    void testInvalidLimitNegative() {
        assertThrows(
                IllegalArgumentException.class, () -> RetrieveConfig.builder().limit(-1).build());
    }

    @Test
    @DisplayName("Should throw exception when scoreThreshold is negative")
    void testInvalidScoreThresholdNegative() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RetrieveConfig.builder().scoreThreshold(-0.1).build());
    }

    @Test
    @DisplayName("Should throw exception when scoreThreshold is greater than 1.0")
    void testInvalidScoreThresholdTooHigh() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RetrieveConfig.builder().scoreThreshold(1.1).build());
    }

    @Test
    @DisplayName("Should accept boundary values for scoreThreshold")
    void testBoundaryScoreThreshold() {
        RetrieveConfig config1 = RetrieveConfig.builder().scoreThreshold(0.0).build();
        RetrieveConfig config2 = RetrieveConfig.builder().scoreThreshold(1.0).build();

        assertEquals(0.0, config1.getScoreThreshold());
        assertEquals(1.0, config2.getScoreThreshold());
    }

    @Test
    @DisplayName("Should allow chaining builder methods")
    void testBuilderChaining() {
        RetrieveConfig config = RetrieveConfig.builder().limit(20).scoreThreshold(0.8).build();

        assertEquals(20, config.getLimit());
        assertEquals(0.8, config.getScoreThreshold());
    }
}
