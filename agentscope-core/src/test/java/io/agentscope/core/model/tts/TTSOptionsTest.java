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
package io.agentscope.core.model.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TTSOptions.
 */
class TTSOptionsTest {

    @Test
    @DisplayName("should build with all options")
    void shouldBuildWithAllOptions() {
        TTSOptions options =
                TTSOptions.builder()
                        .voice("Cherry")
                        .sampleRate(24000)
                        .format("wav")
                        .speed(1.5f)
                        .volume(80f)
                        .pitch(1.2f)
                        .language("Chinese")
                        .build();

        assertEquals("Cherry", options.getVoice());
        assertEquals(24000, options.getSampleRate());
        assertEquals("wav", options.getFormat());
        assertEquals(1.5f, options.getSpeed());
        assertEquals(80f, options.getVolume());
        assertEquals(1.2f, options.getPitch());
        assertEquals("Chinese", options.getLanguage());
    }

    @Test
    @DisplayName("should build with default values")
    void shouldBuildWithDefaults() {
        TTSOptions options = TTSOptions.builder().build();

        assertNotNull(options);
        assertNull(options.getVoice());
        assertNull(options.getSampleRate());
        assertNull(options.getFormat());
        assertNull(options.getSpeed());
        assertNull(options.getVolume());
        assertNull(options.getPitch());
        assertNull(options.getLanguage());
    }

    @Test
    @DisplayName("should build with partial options")
    void shouldBuildWithPartialOptions() {
        TTSOptions options =
                TTSOptions.builder().voice("Serena").sampleRate(16000).language("English").build();

        assertEquals("Serena", options.getVoice());
        assertEquals(16000, options.getSampleRate());
        assertEquals("English", options.getLanguage());
        assertNull(options.getFormat());
        assertNull(options.getSpeed());
    }

    @Test
    @DisplayName("builder method should return new instance")
    void builderShouldReturnNewInstance() {
        TTSOptions.Builder builder1 = TTSOptions.builder();
        TTSOptions.Builder builder2 = TTSOptions.builder();

        assertNotNull(builder1);
        assertNotNull(builder2);
    }
}
