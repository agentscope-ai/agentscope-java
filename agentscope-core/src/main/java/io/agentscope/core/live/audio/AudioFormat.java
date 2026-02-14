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

/**
 * Audio format enumeration for real-time API integration.
 *
 * <p>Defines input/output audio formats supported by various providers:
 *
 * <ul>
 *   <li>DashScope: Input 16kHz 16bit, Output 16kHz 16bit or 24kHz 24bit
 *   <li>OpenAI: Input/Output 24kHz 16bit
 *   <li>Gemini: Input 16kHz 16bit, Output 24kHz 16bit
 *   <li>Doubao: Input 16kHz 16bit, Output 24kHz OGG/Opus or PCM
 * </ul>
 */
public enum AudioFormat {

    // ========== Input Formats ==========

    /** PCM 16kHz 16bit mono - DashScope/Gemini/Doubao input format. */
    PCM_16K_16BIT_MONO("audio/pcm;rate=16000", 16000, 16, 1),

    /** PCM 24kHz 16bit mono - OpenAI input format. */
    PCM_24K_16BIT_MONO("audio/pcm;rate=24000", 24000, 16, 1),

    /** Opus 16kHz mono - Doubao compressed input format. */
    OPUS_16K_MONO("audio/opus", 16000, 16, 1),

    /** G.711 μ-law - OpenAI telephony format. */
    G711_ULAW("audio/g711-ulaw", 8000, 8, 1),

    /** G.711 A-law - OpenAI telephony format. */
    G711_ALAW("audio/g711-alaw", 8000, 8, 1),

    // ========== Output Formats ==========

    /** PCM 16kHz 16bit mono - DashScope Qwen-Omni-Turbo-Realtime output. */
    PCM16_OUTPUT("pcm16", 16000, 16, 1),

    /** PCM 24kHz 24bit mono - DashScope Qwen3-Omni-Flash-Realtime output. */
    PCM24_OUTPUT("pcm24", 24000, 24, 1),

    /** PCM 24kHz 16bit mono - OpenAI/Gemini/Doubao output format. */
    PCM_24K_16BIT_MONO_OUTPUT("pcm_s16le", 24000, 16, 1),

    /** OGG/Opus - Doubao default output format. */
    OGG_OPUS_OUTPUT("ogg_opus", 24000, 16, 1);

    private final String mimeType;
    private final int sampleRate;
    private final int bitDepth;
    private final int channels;

    AudioFormat(String mimeType, int sampleRate, int bitDepth, int channels) {
        this.mimeType = mimeType;
        this.sampleRate = sampleRate;
        this.bitDepth = bitDepth;
        this.channels = channels;
    }

    /**
     * Gets the MIME type.
     *
     * @return the MIME type string
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
     * Gets the bit depth (bits per sample).
     *
     * @return the bit depth
     */
    public int getBitDepth() {
        return bitDepth;
    }

    /**
     * Gets the number of channels.
     *
     * @return the channel count
     */
    public int getChannels() {
        return channels;
    }

    /**
     * Calculates bytes per millisecond for this format.
     *
     * @return bytes per millisecond
     */
    public int getBytesPerMs() {
        return sampleRate * (bitDepth / 8) * channels / 1000;
    }

    /**
     * Calculates the number of bytes for a given duration.
     *
     * @param durationMs duration in milliseconds
     * @return number of bytes
     */
    public int getBytesForDuration(int durationMs) {
        return getBytesPerMs() * durationMs;
    }

    /**
     * Selects the appropriate DashScope output format based on model name.
     *
     * @param modelName the model name
     * @return PCM24_OUTPUT for flash models, PCM16_OUTPUT otherwise
     */
    public static AudioFormat forDashScopeModel(String modelName) {
        if (modelName != null && modelName.toLowerCase().contains("flash")) {
            return PCM24_OUTPUT;
        }
        return PCM16_OUTPUT;
    }

    /**
     * Gets the default DashScope input audio format.
     *
     * <p>Currently DashScope only supports PCM 16kHz 16bit mono for input.
     *
     * @return PCM_16K_16BIT_MONO
     */
    public static AudioFormat dashScopeDefaultInput() {
        return PCM_16K_16BIT_MONO;
    }

    /**
     * Gets the DashScope API value for input audio format.
     *
     * <p>Maps this AudioFormat to the string value expected by DashScope's input_audio_format
     * field. Currently DashScope only supports "pcm16" for input.
     *
     * @return the DashScope API value for input format
     * @throws IllegalStateException if this format is not supported as DashScope input
     */
    public String getDashScopeInputApiValue() {
        if (this == PCM_16K_16BIT_MONO) {
            return "pcm16";
        }
        throw new IllegalStateException(
                "AudioFormat " + this.name() + " is not supported as DashScope input format");
    }

    /**
     * Gets the DashScope API value for output audio format.
     *
     * <p>Maps this AudioFormat to the string value expected by DashScope's output_audio_format
     * field. DashScope supports "pcm16" (Turbo models) and "pcm24" (Flash models).
     *
     * @return the DashScope API value for output format
     * @throws IllegalStateException if this format is not supported as DashScope output
     */
    public String getDashScopeOutputApiValue() {
        if (this == PCM16_OUTPUT || this == PCM24_OUTPUT) {
            return mimeType; // "pcm16" or "pcm24" - already matches API values
        }
        throw new IllegalStateException(
                "AudioFormat " + this.name() + " is not supported as DashScope output format");
    }
}
