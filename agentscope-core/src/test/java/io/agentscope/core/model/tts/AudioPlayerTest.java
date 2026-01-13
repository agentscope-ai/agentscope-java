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

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.URLSource;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AudioPlayer.
 *
 * <p>Note: Actual audio playback is not tested as it requires audio hardware.
 * These tests focus on builder and basic logic.
 */
class AudioPlayerTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should build with default values")
        void shouldBuildWithDefaults() {
            AudioPlayer player = AudioPlayer.builder().build();

            assertNotNull(player);
        }

        @Test
        @DisplayName("should build with custom values")
        void shouldBuildWithCustomValues() {
            AudioPlayer player =
                    AudioPlayer.builder()
                            .sampleRate(48000)
                            .sampleSizeInBits(16)
                            .channels(2)
                            .signed(true)
                            .bigEndian(true)
                            .build();

            assertNotNull(player);
        }

        @Test
        @DisplayName("should build with common TTS settings")
        void shouldBuildWithCommonTTSSettings() {
            AudioPlayer player =
                    AudioPlayer.builder()
                            .sampleRate(24000)
                            .sampleSizeInBits(16)
                            .channels(1)
                            .signed(true)
                            .bigEndian(false)
                            .build();

            assertNotNull(player);
        }
    }

    @Nested
    @DisplayName("Play AudioBlock Tests")
    class PlayAudioBlockTests {

        @Test
        @DisplayName("should handle null audio block")
        void shouldHandleNullAudioBlock() {
            AudioPlayer player = AudioPlayer.builder().build();

            // Should not throw
            player.play((AudioBlock) null);
        }

        @Test
        @DisplayName("should handle audio block with valid Base64Source")
        void shouldHandleAudioBlockWithValidBase64Source() {
            AudioPlayer player = AudioPlayer.builder().build();
            AudioBlock audioBlock =
                    AudioBlock.builder()
                            .source(
                                    Base64Source.builder()
                                            .mediaType("audio/wav")
                                            .data("dGVzdA==")
                                            .build())
                            .build();

            // Should not throw (though actual audio playback requires hardware)
            player.play(audioBlock);
        }

        @Test
        @DisplayName("should handle audio block with URL source")
        void shouldHandleAudioBlockWithUrlSource() {
            AudioPlayer player = AudioPlayer.builder().build();
            AudioBlock audioBlock =
                    AudioBlock.builder()
                            .source(new URLSource("https://example.com/audio.wav"))
                            .build();

            // URL sources are not supported for direct playback, should not throw
            player.play(audioBlock);
        }

        @Test
        @DisplayName("should handle audio block with empty base64 data")
        void shouldHandleAudioBlockWithEmptyBase64Data() {
            AudioPlayer player = AudioPlayer.builder().build();
            AudioBlock audioBlock =
                    AudioBlock.builder()
                            .source(Base64Source.builder().mediaType("audio/wav").data("").build())
                            .build();

            // Should not throw
            player.play(audioBlock);
        }
    }

    @Nested
    @DisplayName("Play Bytes Tests")
    class PlayBytesTests {

        @Test
        @DisplayName("should handle null bytes")
        void shouldHandleNullBytes() {
            AudioPlayer player = AudioPlayer.builder().build();

            // Should not throw
            player.play((byte[]) null);
        }

        @Test
        @DisplayName("should handle empty bytes")
        void shouldHandleEmptyBytes() {
            AudioPlayer player = AudioPlayer.builder().build();

            // Should not throw
            player.play(new byte[0]);
        }
    }

    @Nested
    @DisplayName("Stop Tests")
    class StopTests {

        @Test
        @DisplayName("should stop without error when not started")
        void shouldStopWithoutErrorWhenNotStarted() {
            AudioPlayer player = AudioPlayer.builder().build();

            // Should not throw
            player.stop();
        }
    }

    @Nested
    @DisplayName("Drain Tests")
    class DrainTests {

        @Test
        @DisplayName("should drain without error when not started")
        void shouldDrainWithoutErrorWhenNotStarted() {
            AudioPlayer player = AudioPlayer.builder().build();

            // Should not throw - but will do nothing since not running
            player.drain();
        }
    }

    @Nested
    @DisplayName("Audio Data Decoding Tests")
    class AudioDataDecodingTests {

        @Test
        @DisplayName("should decode base64 audio data")
        void shouldDecodeBase64AudioData() {
            byte[] originalData = "test audio data".getBytes();
            String base64Data = Base64.getEncoder().encodeToString(originalData);

            // Verify decoding works correctly
            byte[] decoded = Base64.getDecoder().decode(base64Data);
            assertNotNull(decoded);
        }
    }
}
