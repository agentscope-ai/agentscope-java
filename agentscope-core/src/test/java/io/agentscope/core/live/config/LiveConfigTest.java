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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LiveConfig Tests")
class LiveConfigTest {

    @Test
    @DisplayName("Should create default config")
    void shouldCreateDefaultConfig() {
        LiveConfig config = LiveConfig.defaults();

        assertNull(config.getVoice());
        assertNull(config.getInstructions());
        assertTrue(config.isEnableInputTranscription());
        assertTrue(config.isEnableOutputTranscription());
        assertTrue(config.isAutoReconnect());
        assertNotNull(config.getReconnectConfig());
    }

    @Test
    @DisplayName("Should build custom config")
    void shouldBuildCustomConfig() {
        LiveConfig config =
                LiveConfig.builder()
                        .voice("alloy")
                        .instructions("You are a helpful assistant.")
                        .enableInputTranscription(false)
                        .autoReconnect(false)
                        .build();

        assertEquals("alloy", config.getVoice());
        assertEquals("You are a helpful assistant.", config.getInstructions());
        assertFalse(config.isEnableInputTranscription());
        assertFalse(config.isAutoReconnect());
    }

    @Test
    @DisplayName("Should convert to builder and back")
    void shouldConvertToBuilderAndBack() {
        LiveConfig original = LiveConfig.builder().voice("echo").instructions("Test").build();

        LiveConfig copy = original.toBuilder().voice("nova").build();

        assertEquals("nova", copy.getVoice());
        assertEquals("Test", copy.getInstructions());
    }

    @Test
    @DisplayName("Should include generation config")
    void shouldIncludeGenerationConfig() {
        GenerationConfig genConfig =
                GenerationConfig.builder().temperature(0.5f).maxTokens(100).build();

        LiveConfig config = LiveConfig.builder().generationConfig(genConfig).build();

        assertNotNull(config.getGenerationConfig());
        assertEquals(0.5f, config.getGenerationConfig().getTemperature());
        assertEquals(100, config.getGenerationConfig().getMaxTokens());
    }

    @Test
    @DisplayName("Should use custom reconnect config")
    void shouldUseCustomReconnectConfig() {
        ReconnectConfig reconnectConfig = ReconnectConfig.builder().maxAttempts(5).build();

        LiveConfig config = LiveConfig.builder().reconnectConfig(reconnectConfig).build();

        assertEquals(5, config.getReconnectConfig().getMaxAttempts());
    }
}
