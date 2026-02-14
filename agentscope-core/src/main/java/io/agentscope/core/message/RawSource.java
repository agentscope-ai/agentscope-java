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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Arrays;
import java.util.Objects;

/**
 * Raw byte data source for real-time audio streaming.
 *
 * <p>Unlike {@link Base64Source} which stores encoded data, RawSource stores raw PCM audio bytes
 * directly. This is more efficient for real-time streaming scenarios where audio data is
 * continuously flowing.
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * // Create from raw PCM data (16kHz, 16-bit, mono)
 * byte[] pcmData = microphone.read();
 * RawSource source = RawSource.pcm16kMono(pcmData);
 *
 * // Use with AudioBlock
 * Msg audioMsg = Msg.builder()
 *     .role(MsgRole.USER)
 *     .content(AudioBlock.builder()
 *         .source(source)
 *         .build())
 *     .build();
 * }</pre>
 *
 * <p>Supported formats:
 *
 * <ul>
 *   <li>PCM 16kHz 16-bit mono - DashScope, Gemini, Doubao input
 *   <li>PCM 24kHz 16-bit mono - OpenAI input/output
 *   <li>PCM 24kHz 24-bit mono - DashScope Flash output
 * </ul>
 *
 * @see AudioBlock
 */
public final class RawSource extends Source {

    private final byte[] data;
    private final String mimeType;
    private final int sampleRate;
    private final int bitDepth;
    private final int channels;

    /**
     * Creates a new RawSource.
     *
     * @param data raw audio bytes
     * @param mimeType MIME type (e.g., "audio/pcm")
     * @param sampleRate sample rate in Hz (e.g., 16000, 24000)
     * @param bitDepth bits per sample (e.g., 16, 24)
     * @param channels number of channels (typically 1 for mono)
     */
    @JsonCreator
    public RawSource(
            @JsonProperty("data") byte[] data,
            @JsonProperty("mimeType") String mimeType,
            @JsonProperty("sampleRate") int sampleRate,
            @JsonProperty("bitDepth") int bitDepth,
            @JsonProperty("channels") int channels) {
        this.data = Objects.requireNonNull(data, "data cannot be null");
        this.mimeType = mimeType != null ? mimeType : "audio/pcm";
        this.sampleRate = sampleRate;
        this.bitDepth = bitDepth;
        this.channels = channels;
    }

    /**
     * Gets the raw audio data.
     *
     * @return copy of the raw audio bytes
     */
    public byte[] getData() {
        return Arrays.copyOf(data, data.length);
    }

    /**
     * Gets the raw audio data without copying.
     *
     * <p>Warning: Do not modify the returned array.
     *
     * @return the raw audio bytes (not a copy)
     */
    public byte[] getDataUnsafe() {
        return data;
    }

    /**
     * Gets the MIME type.
     *
     * @return the MIME type
     */
    public String getMimeType() {
        return mimeType;
    }

    /**
     * Gets the sample rate in Hz.
     *
     * @return the sample rate
     */
    public int getSampleRate() {
        return sampleRate;
    }

    /**
     * Gets the bit depth.
     *
     * @return bits per sample
     */
    public int getBitDepth() {
        return bitDepth;
    }

    /**
     * Gets the number of channels.
     *
     * @return number of channels
     */
    public int getChannels() {
        return channels;
    }

    /**
     * Gets the data length in bytes.
     *
     * @return data length
     */
    public int getLength() {
        return data.length;
    }

    /**
     * Gets the duration in milliseconds.
     *
     * @return duration in ms
     */
    public long getDurationMs() {
        if (sampleRate == 0 || bitDepth == 0 || channels == 0) {
            return 0;
        }
        int bytesPerSample = (bitDepth / 8) * channels;
        int samples = data.length / bytesPerSample;
        return (samples * 1000L) / sampleRate;
    }

    // ==================== Static Factory Methods ====================

    /**
     * Creates a RawSource for PCM 16kHz 16-bit mono audio.
     *
     * <p>This is the standard input format for DashScope, Gemini, and Doubao.
     *
     * @param data raw PCM bytes
     * @return a new RawSource
     */
    public static RawSource pcm16kMono(byte[] data) {
        return new RawSource(data, "audio/pcm", 16000, 16, 1);
    }

    /**
     * Creates a RawSource for PCM 24kHz 16-bit mono audio.
     *
     * <p>This is the standard format for OpenAI input/output.
     *
     * @param data raw PCM bytes
     * @return a new RawSource
     */
    public static RawSource pcm24kMono(byte[] data) {
        return new RawSource(data, "audio/pcm", 24000, 16, 1);
    }

    /**
     * Creates a RawSource for PCM 24kHz 24-bit mono audio.
     *
     * <p>This is the output format for DashScope Flash models.
     *
     * @param data raw PCM bytes
     * @return a new RawSource
     */
    public static RawSource pcm24k24bitMono(byte[] data) {
        return new RawSource(data, "audio/pcm", 24000, 24, 1);
    }

    /**
     * Creates a builder for custom audio formats.
     *
     * @return a new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for RawSource. */
    public static class Builder {
        private byte[] data;
        private String mimeType = "audio/pcm";
        private int sampleRate = 16000;
        private int bitDepth = 16;
        private int channels = 1;

        public Builder data(byte[] data) {
            this.data = data;
            return this;
        }

        public Builder mimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        public Builder bitDepth(int bitDepth) {
            this.bitDepth = bitDepth;
            return this;
        }

        public Builder channels(int channels) {
            this.channels = channels;
            return this;
        }

        public RawSource build() {
            return new RawSource(data, mimeType, sampleRate, bitDepth, channels);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RawSource rawSource = (RawSource) o;
        return sampleRate == rawSource.sampleRate
                && bitDepth == rawSource.bitDepth
                && channels == rawSource.channels
                && Arrays.equals(data, rawSource.data)
                && Objects.equals(mimeType, rawSource.mimeType);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mimeType, sampleRate, bitDepth, channels);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }

    @Override
    public String toString() {
        return "RawSource{"
                + "length="
                + data.length
                + ", mimeType='"
                + mimeType
                + '\''
                + ", sampleRate="
                + sampleRate
                + ", bitDepth="
                + bitDepth
                + ", channels="
                + channels
                + ", durationMs="
                + getDurationMs()
                + '}';
    }
}
