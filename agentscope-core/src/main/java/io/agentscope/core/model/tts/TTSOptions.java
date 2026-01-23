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

/**
 * Options for TTS synthesis.
 *
 * <p>This class provides configuration options for text-to-speech synthesis,
 * including voice selection, audio format, and speech parameters.
 *
 * <p>Example usage:
 * <pre>{@code
 * TTSOptions options = TTSOptions.builder()
 *     .voice("Cherry")
 *     .sampleRate(24000)
 *     .format("wav")
 *     .speed(1.0f)
 *     .build();
 * }</pre>
 */
public class TTSOptions {

    /** Voice name/ID for synthesis (e.g., "Cherry", "zhimao"). */
    private final String voice;

    /** Audio sample rate in Hz (e.g., 16000, 24000, 48000). */
    private final Integer sampleRate;

    /** Output audio format (e.g., "wav", "mp3", "pcm"). */
    private final String format;

    /** Speech speed/rate multiplier (0.5-2.0, default 1.0). */
    private final Float speed;

    /** Audio volume (0-100, default 50). */
    private final Float volume;

    /** Speech pitch multiplier (0.5-2.0, default 1.0). */
    private final Float pitch;

    /** Language type for synthesis (e.g., "Chinese", "English", "Japanese"). */
    private final String language;

    private TTSOptions(Builder builder) {
        this.voice = builder.voice;
        this.sampleRate = builder.sampleRate;
        this.format = builder.format;
        this.speed = builder.speed;
        this.volume = builder.volume;
        this.pitch = builder.pitch;
        this.language = builder.language;
    }

    public String getVoice() {
        return voice;
    }

    public Integer getSampleRate() {
        return sampleRate;
    }

    public String getFormat() {
        return format;
    }

    public Float getSpeed() {
        return speed;
    }

    public Float getVolume() {
        return volume;
    }

    public Float getPitch() {
        return pitch;
    }

    public String getLanguage() {
        return language;
    }

    /**
     * Creates a new builder for TTSOptions.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing TTSOptions instances.
     */
    public static class Builder {
        private String voice;
        private Integer sampleRate;
        private String format;
        private Float speed;
        private Float volume;
        private Float pitch;
        private String language;

        /**
         * Sets the voice for synthesis.
         *
         * @param voice the voice name/ID
         * @return this builder
         */
        public Builder voice(String voice) {
            this.voice = voice;
            return this;
        }

        /**
         * Sets the audio sample rate.
         *
         * @param sampleRate sample rate in Hz
         * @return this builder
         */
        public Builder sampleRate(Integer sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Sets the output audio format.
         *
         * @param format audio format (e.g., "wav", "mp3")
         * @return this builder
         */
        public Builder format(String format) {
            this.format = format;
            return this;
        }

        /**
         * Sets the speech speed.
         *
         * @param speed speed multiplier (0.5-2.0)
         * @return this builder
         */
        public Builder speed(Float speed) {
            this.speed = speed;
            return this;
        }

        /**
         * Sets the audio volume.
         *
         * @param volume volume level (0-100)
         * @return this builder
         */
        public Builder volume(Float volume) {
            this.volume = volume;
            return this;
        }

        /**
         * Sets the speech pitch.
         *
         * @param pitch pitch multiplier (0.5-2.0)
         * @return this builder
         */
        public Builder pitch(Float pitch) {
            this.pitch = pitch;
            return this;
        }

        /**
         * Sets the language type.
         *
         * @param language language code (e.g., "Chinese")
         * @return this builder
         */
        public Builder language(String language) {
            this.language = language;
            return this;
        }

        /**
         * Builds the TTSOptions instance.
         *
         * @return a new TTSOptions
         */
        public TTSOptions build() {
            return new TTSOptions(this);
        }
    }
}
