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
package io.agentscope.core.live.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AudioFormat Tests")
class AudioFormatTest {

    @Test
    @DisplayName("Should have correct properties for PCM_16K_16BIT_MONO")
    void shouldHaveCorrectPropertiesForPcm16k() {
        AudioFormat format = AudioFormat.PCM_16K_16BIT_MONO;

        assertEquals("audio/pcm;rate=16000", format.getMimeType());
        assertEquals(16000, format.getSampleRate());
        assertEquals(16, format.getBitDepth());
        assertEquals(1, format.getChannels());
    }

    @Test
    @DisplayName("Should have correct properties for PCM_24K_16BIT_MONO")
    void shouldHaveCorrectPropertiesForPcm24k() {
        AudioFormat format = AudioFormat.PCM_24K_16BIT_MONO;

        assertEquals("audio/pcm;rate=24000", format.getMimeType());
        assertEquals(24000, format.getSampleRate());
        assertEquals(16, format.getBitDepth());
        assertEquals(1, format.getChannels());
    }

    @Test
    @DisplayName("Should have correct properties for G711_ULAW")
    void shouldHaveCorrectPropertiesForG711Ulaw() {
        AudioFormat format = AudioFormat.G711_ULAW;

        assertEquals("audio/g711-ulaw", format.getMimeType());
        assertEquals(8000, format.getSampleRate());
        assertEquals(8, format.getBitDepth());
        assertEquals(1, format.getChannels());
    }

    @Test
    @DisplayName("Should have correct properties for G711_ALAW")
    void shouldHaveCorrectPropertiesForG711Alaw() {
        AudioFormat format = AudioFormat.G711_ALAW;

        assertEquals("audio/g711-alaw", format.getMimeType());
        assertEquals(8000, format.getSampleRate());
        assertEquals(8, format.getBitDepth());
        assertEquals(1, format.getChannels());
    }

    @Test
    @DisplayName("Should calculate bytes per millisecond correctly")
    void shouldCalculateBytesPerMs() {
        // 16kHz, 16bit, mono = 16000 * 2 * 1 / 1000 = 32 bytes/ms
        assertEquals(32, AudioFormat.PCM_16K_16BIT_MONO.getBytesPerMs());

        // 24kHz, 16bit, mono = 24000 * 2 * 1 / 1000 = 48 bytes/ms
        assertEquals(48, AudioFormat.PCM_24K_16BIT_MONO.getBytesPerMs());

        // 8kHz, 8bit, mono = 8000 * 1 * 1 / 1000 = 8 bytes/ms
        assertEquals(8, AudioFormat.G711_ULAW.getBytesPerMs());
    }

    @Test
    @DisplayName("Should calculate bytes for duration correctly")
    void shouldCalculateBytesForDuration() {
        // 100ms of 16kHz 16bit mono = 32 * 100 = 3200 bytes
        assertEquals(3200, AudioFormat.PCM_16K_16BIT_MONO.getBytesForDuration(100));

        // 100ms of 24kHz 16bit mono = 48 * 100 = 4800 bytes
        assertEquals(4800, AudioFormat.PCM_24K_16BIT_MONO.getBytesForDuration(100));
    }

    @Test
    @DisplayName("Should select PCM24_OUTPUT for flash models")
    void shouldSelectPcm24ForFlashModels() {
        assertEquals(
                AudioFormat.PCM24_OUTPUT,
                AudioFormat.forDashScopeModel("qwen3-omni-flash-realtime"));
        assertEquals(
                AudioFormat.PCM24_OUTPUT,
                AudioFormat.forDashScopeModel("Qwen3-Omni-Flash-Realtime"));
    }

    @Test
    @DisplayName("Should select PCM16_OUTPUT for non-flash models")
    void shouldSelectPcm16ForNonFlashModels() {
        assertEquals(
                AudioFormat.PCM16_OUTPUT,
                AudioFormat.forDashScopeModel("qwen-omni-turbo-realtime"));
        assertEquals(AudioFormat.PCM16_OUTPUT, AudioFormat.forDashScopeModel(null));
    }

    @Test
    @DisplayName("Should return correct DashScope default input format")
    void shouldReturnDashScopeDefaultInput() {
        assertEquals(AudioFormat.PCM_16K_16BIT_MONO, AudioFormat.dashScopeDefaultInput());
    }

    @Test
    @DisplayName("Should return correct DashScope input API value")
    void shouldReturnDashScopeInputApiValue() {
        assertEquals("pcm16", AudioFormat.PCM_16K_16BIT_MONO.getDashScopeInputApiValue());
    }

    @Test
    @DisplayName("Should throw for unsupported DashScope input format")
    void shouldThrowForUnsupportedDashScopeInputFormat() {
        assertThrows(
                IllegalStateException.class,
                () -> AudioFormat.PCM_24K_16BIT_MONO.getDashScopeInputApiValue());
    }

    @Test
    @DisplayName("Should return correct DashScope output API value")
    void shouldReturnDashScopeOutputApiValue() {
        assertEquals("pcm16", AudioFormat.PCM16_OUTPUT.getDashScopeOutputApiValue());
        assertEquals("pcm24", AudioFormat.PCM24_OUTPUT.getDashScopeOutputApiValue());
    }

    @Test
    @DisplayName("Should throw for unsupported DashScope output format")
    void shouldThrowForUnsupportedDashScopeOutputFormat() {
        assertThrows(
                IllegalStateException.class,
                () -> AudioFormat.PCM_16K_16BIT_MONO.getDashScopeOutputApiValue());
    }
}
