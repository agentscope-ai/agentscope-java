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
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GenerationConfig Tests")
class GenerationConfigTest {

    @Test
    @DisplayName("Should create default config with temperature")
    void shouldCreateDefaultConfig() {
        GenerationConfig config = GenerationConfig.defaults();

        assertEquals(0.8f, config.getTemperature());
        assertNull(config.getTopP());
        assertNull(config.getTopK());
        assertNull(config.getMaxTokens());
    }

    @Test
    @DisplayName("Should build custom config")
    void shouldBuildCustomConfig() {
        GenerationConfig config =
                GenerationConfig.builder()
                        .temperature(0.5f)
                        .topP(0.9f)
                        .topK(50)
                        .maxTokens(1000)
                        .build();

        assertEquals(0.5f, config.getTemperature());
        assertEquals(0.9f, config.getTopP());
        assertEquals(50, config.getTopK());
        assertEquals(1000, config.getMaxTokens());
    }

    @Test
    @DisplayName("Should convert to builder and back")
    void shouldConvertToBuilderAndBack() {
        GenerationConfig original =
                GenerationConfig.builder().temperature(0.7f).maxTokens(500).build();

        GenerationConfig copy = original.toBuilder().temperature(0.6f).build();

        assertEquals(0.6f, copy.getTemperature());
        assertEquals(500, copy.getMaxTokens());
    }

    @Test
    @DisplayName("Should handle null values")
    void shouldHandleNullValues() {
        GenerationConfig config = GenerationConfig.builder().build();

        assertNull(config.getTemperature());
        assertNull(config.getTopP());
        assertNull(config.getTopK());
        assertNull(config.getMaxTokens());
    }

    @Test
    @DisplayName("Should implement equals and hashCode")
    void shouldImplementEqualsAndHashCode() {
        GenerationConfig config1 =
                GenerationConfig.builder().temperature(0.8f).maxTokens(100).build();
        GenerationConfig config2 =
                GenerationConfig.builder().temperature(0.8f).maxTokens(100).build();
        GenerationConfig config3 =
                GenerationConfig.builder().temperature(0.9f).maxTokens(100).build();

        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());
        assertNotEquals(config1, config3);
    }
}
