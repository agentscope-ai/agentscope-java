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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AudioResampler Tests")
class AudioResamplerTest {

    @Test
    @DisplayName("Should resample 16kHz to 24kHz")
    void shouldResample16kTo24k() {
        AudioResampler resampler = AudioResampler.linear();

        // 100ms of 16kHz 16-bit mono = 3200 bytes
        byte[] input = new byte[3200];
        // Fill with simple sine wave pattern
        for (int i = 0; i < 1600; i++) {
            short sample = (short) (Math.sin(2 * Math.PI * 440 * i / 16000) * 16000);
            input[i * 2] = (byte) (sample & 0xFF);
            input[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }

        byte[] output =
                resampler.resample(
                        input, AudioFormat.PCM_16K_16BIT_MONO, AudioFormat.PCM_24K_16BIT_MONO);

        // 100ms of 24kHz 16-bit mono = 4800 bytes
        assertEquals(4800, output.length);
    }

    @Test
    @DisplayName("Should return same data when no resampling needed")
    void shouldReturnSameDataWhenNoResamplingNeeded() {
        AudioResampler resampler = AudioResampler.linear();
        byte[] input = new byte[3200];

        byte[] output =
                resampler.resample(
                        input, AudioFormat.PCM_16K_16BIT_MONO, AudioFormat.PCM_16K_16BIT_MONO);

        assertSame(input, output);
    }

    @Test
    @DisplayName("Should check if resampling is needed")
    void shouldCheckIfResamplingIsNeeded() {
        assertTrue(
                AudioResampler.needsResampling(
                        AudioFormat.PCM_16K_16BIT_MONO, AudioFormat.PCM_24K_16BIT_MONO));

        assertFalse(
                AudioResampler.needsResampling(
                        AudioFormat.PCM_16K_16BIT_MONO, AudioFormat.PCM_16K_16BIT_MONO));
    }

    @Test
    @DisplayName("Should get singleton instance")
    void shouldGetSingletonInstance() {
        AudioResampler r1 = AudioResampler.linear();
        AudioResampler r2 = AudioResampler.linear();

        assertSame(r1, r2);
    }

    @Test
    @DisplayName("Should resample 24kHz to 16kHz (downsampling)")
    void shouldResample24kTo16k() {
        AudioResampler resampler = AudioResampler.linear();

        // 100ms of 24kHz 16-bit mono = 4800 bytes
        byte[] input = new byte[4800];
        // Fill with simple sine wave pattern
        for (int i = 0; i < 2400; i++) {
            short sample = (short) (Math.sin(2 * Math.PI * 440 * i / 24000) * 16000);
            input[i * 2] = (byte) (sample & 0xFF);
            input[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }

        byte[] output =
                resampler.resample(
                        input, AudioFormat.PCM_24K_16BIT_MONO, AudioFormat.PCM_16K_16BIT_MONO);

        // 100ms of 16kHz 16-bit mono = 3200 bytes
        assertEquals(3200, output.length);
    }

    @Test
    @DisplayName("Should handle empty input")
    void shouldHandleEmptyInput() {
        AudioResampler resampler = AudioResampler.linear();
        byte[] input = new byte[0];

        byte[] output =
                resampler.resample(
                        input, AudioFormat.PCM_16K_16BIT_MONO, AudioFormat.PCM_24K_16BIT_MONO);

        assertEquals(0, output.length);
    }
}
