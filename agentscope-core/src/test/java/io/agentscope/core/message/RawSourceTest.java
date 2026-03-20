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
package io.agentscope.core.message;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RawSource Tests")
class RawSourceTest {

    @Test
    @DisplayName("Should create PCM 16kHz mono source")
    void shouldCreatePcm16kMonoSource() {
        byte[] data = new byte[3200]; // 100ms of 16kHz 16-bit mono
        RawSource source = RawSource.pcm16kMono(data);

        assertEquals(16000, source.getSampleRate());
        assertEquals(16, source.getBitDepth());
        assertEquals(1, source.getChannels());
        assertEquals("audio/pcm", source.getMimeType());
        assertEquals(3200, source.getLength());
        assertEquals(100, source.getDurationMs());
    }

    @Test
    @DisplayName("Should create PCM 24kHz mono source")
    void shouldCreatePcm24kMonoSource() {
        byte[] data = new byte[4800]; // 100ms of 24kHz 16-bit mono
        RawSource source = RawSource.pcm24kMono(data);

        assertEquals(24000, source.getSampleRate());
        assertEquals(16, source.getBitDepth());
        assertEquals(1, source.getChannels());
        assertEquals(100, source.getDurationMs());
    }

    @Test
    @DisplayName("Should create PCM 24kHz 24-bit mono source")
    void shouldCreatePcm24k24bitMonoSource() {
        byte[] data = new byte[7200]; // 100ms of 24kHz 24-bit mono
        RawSource source = RawSource.pcm24k24bitMono(data);

        assertEquals(24000, source.getSampleRate());
        assertEquals(24, source.getBitDepth());
        assertEquals(1, source.getChannels());
        assertEquals(100, source.getDurationMs());
    }

    @Test
    @DisplayName("Should calculate duration correctly")
    void shouldCalculateDurationCorrectly() {
        // 1 second of 16kHz 16-bit mono = 32000 bytes
        byte[] data = new byte[32000];
        RawSource source = RawSource.pcm16kMono(data);

        assertEquals(1000, source.getDurationMs());
    }

    @Test
    @DisplayName("Should return copy of data")
    void shouldReturnCopyOfData() {
        byte[] original = {1, 2, 3, 4};
        RawSource source = RawSource.pcm16kMono(original);

        byte[] retrieved = source.getData();
        retrieved[0] = 99;

        // Original should be unchanged
        assertEquals(1, source.getData()[0]);
    }

    @Test
    @DisplayName("Should return unsafe data reference")
    void shouldReturnUnsafeDataReference() {
        byte[] original = {1, 2, 3, 4};
        RawSource source = RawSource.pcm16kMono(original);

        byte[] unsafe = source.getDataUnsafe();
        assertArrayEquals(original, unsafe);
    }

    @Test
    @DisplayName("Should build custom source")
    void shouldBuildCustomSource() {
        byte[] data = new byte[100];
        RawSource source =
                RawSource.builder()
                        .data(data)
                        .sampleRate(8000)
                        .bitDepth(8)
                        .channels(2)
                        .mimeType("audio/wav")
                        .build();

        assertEquals(8000, source.getSampleRate());
        assertEquals(8, source.getBitDepth());
        assertEquals(2, source.getChannels());
        assertEquals("audio/wav", source.getMimeType());
    }

    @Test
    @DisplayName("Should throw on null data")
    void shouldThrowOnNullData() {
        assertThrows(NullPointerException.class, () -> RawSource.pcm16kMono(null));
    }

    @Test
    @DisplayName("Should handle zero duration for invalid parameters")
    void shouldHandleZeroDurationForInvalidParameters() {
        RawSource source = new RawSource(new byte[100], "audio/pcm", 0, 16, 1);
        assertEquals(0, source.getDurationMs());
    }

    @Test
    @DisplayName("Should implement equals correctly")
    void shouldImplementEqualsCorrectly() {
        byte[] data = {1, 2, 3, 4};
        RawSource source1 = RawSource.pcm16kMono(data);
        RawSource source2 = RawSource.pcm16kMono(data);
        RawSource source3 = RawSource.pcm24kMono(data);

        assertEquals(source1, source2);
        assertNotEquals(source1, source3);
    }

    @Test
    @DisplayName("Should implement hashCode correctly")
    void shouldImplementHashCodeCorrectly() {
        byte[] data = {1, 2, 3, 4};
        RawSource source1 = RawSource.pcm16kMono(data);
        RawSource source2 = RawSource.pcm16kMono(data);

        assertEquals(source1.hashCode(), source2.hashCode());
    }

    @Test
    @DisplayName("Should work with AudioBlock")
    void shouldWorkWithAudioBlock() {
        byte[] data = new byte[3200];
        RawSource source = RawSource.pcm16kMono(data);
        AudioBlock audioBlock = AudioBlock.builder().source(source).build();

        assertInstanceOf(RawSource.class, audioBlock.getSource());
        RawSource retrieved = (RawSource) audioBlock.getSource();
        assertEquals(16000, retrieved.getSampleRate());
    }
}
