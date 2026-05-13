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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.URLSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TTSResponse.
 */
class TTSResponseTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should build with all properties")
        void shouldBuildWithAllProperties() {
            byte[] data = "audio".getBytes();
            TTSResponse response =
                    TTSResponse.builder()
                            .audioData(data)
                            .audioUrl("https://example.com/audio.wav")
                            .format("wav")
                            .sampleRate(24000)
                            .durationMs(1000L)
                            .requestId("req-123")
                            .build();

            assertArrayEquals(data, response.getAudioData());
            assertEquals("https://example.com/audio.wav", response.getAudioUrl());
            assertEquals("wav", response.getFormat());
            assertEquals(24000, response.getSampleRate());
            assertEquals(1000L, response.getDurationMs());
            assertEquals("req-123", response.getRequestId());
        }

        @Test
        @DisplayName("should build with minimal properties")
        void shouldBuildWithMinimalProperties() {
            TTSResponse response = TTSResponse.builder().requestId("req-456").build();

            assertNotNull(response);
            assertEquals("req-456", response.getRequestId());
        }
    }

    @Nested
    @DisplayName("toAudioBlock Tests")
    class ToAudioBlockTests {

        @Test
        @DisplayName("should convert base64 audio to AudioBlock")
        void shouldConvertBase64ToAudioBlock() {
            byte[] audioData = "test audio data".getBytes();
            TTSResponse response = TTSResponse.builder().audioData(audioData).format("wav").build();

            AudioBlock audioBlock = response.toAudioBlock();

            assertNotNull(audioBlock);
            assertNotNull(audioBlock.getSource());
            assertEquals(Base64Source.class, audioBlock.getSource().getClass());
        }

        @Test
        @DisplayName("should convert URL to AudioBlock")
        void shouldConvertUrlToAudioBlock() {
            TTSResponse response =
                    TTSResponse.builder()
                            .audioUrl("https://example.com/audio.mp3")
                            .format("mp3")
                            .build();

            AudioBlock audioBlock = response.toAudioBlock();

            assertNotNull(audioBlock);
            assertNotNull(audioBlock.getSource());
            assertEquals(URLSource.class, audioBlock.getSource().getClass());
        }

        @Test
        @DisplayName("should throw when no audio data or URL")
        void shouldThrowWhenNoAudioData() {
            TTSResponse response = TTSResponse.builder().requestId("test").build();

            assertThrows(IllegalStateException.class, response::toAudioBlock);
        }

        @Test
        @DisplayName("should prefer audio data over URL")
        void shouldPreferAudioDataOverUrl() {
            byte[] audioData = "audio bytes".getBytes();
            TTSResponse response =
                    TTSResponse.builder()
                            .audioData(audioData)
                            .audioUrl("https://example.com/audio.wav")
                            .format("wav")
                            .build();

            AudioBlock audioBlock = response.toAudioBlock();

            assertNotNull(audioBlock);
            assertEquals(Base64Source.class, audioBlock.getSource().getClass());
        }

        @Test
        @DisplayName("should handle different audio formats")
        void shouldHandleDifferentFormats() {
            byte[] audioData = "audio".getBytes();

            // Test MP3
            TTSResponse mp3Response =
                    TTSResponse.builder().audioData(audioData).format("mp3").build();
            AudioBlock mp3Block = mp3Response.toAudioBlock();
            Base64Source mp3Source = (Base64Source) mp3Block.getSource();
            assertEquals("audio/mpeg", mp3Source.getMediaType());

            // Test OGG
            TTSResponse oggResponse =
                    TTSResponse.builder().audioData(audioData).format("ogg").build();
            AudioBlock oggBlock = oggResponse.toAudioBlock();
            Base64Source oggSource = (Base64Source) oggBlock.getSource();
            assertEquals("audio/ogg", oggSource.getMediaType());

            // Test PCM
            TTSResponse pcmResponse =
                    TTSResponse.builder().audioData(audioData).format("pcm").build();
            AudioBlock pcmBlock = pcmResponse.toAudioBlock();
            Base64Source pcmSource = (Base64Source) pcmBlock.getSource();
            assertEquals("audio/pcm", pcmSource.getMediaType());

            // Test WAV
            TTSResponse wavResponse =
                    TTSResponse.builder().audioData(audioData).format("wav").build();
            AudioBlock wavBlock = wavResponse.toAudioBlock();
            Base64Source wavSource = (Base64Source) wavBlock.getSource();
            assertEquals("audio/wav", wavSource.getMediaType());

            // Test unknown format
            TTSResponse unknownResponse =
                    TTSResponse.builder().audioData(audioData).format("flac").build();
            AudioBlock unknownBlock = unknownResponse.toAudioBlock();
            Base64Source unknownSource = (Base64Source) unknownBlock.getSource();
            assertEquals("audio/flac", unknownSource.getMediaType());
        }

        @Test
        @DisplayName("should default to wav when format is null")
        void shouldDefaultToWavWhenFormatNull() {
            byte[] audioData = "audio".getBytes();
            TTSResponse response = TTSResponse.builder().audioData(audioData).build();

            AudioBlock audioBlock = response.toAudioBlock();
            Base64Source source = (Base64Source) audioBlock.getSource();

            assertEquals("audio/wav", source.getMediaType());
        }
    }
}
