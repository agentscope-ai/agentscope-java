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

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.URLSource;
import java.util.Base64;

/**
 * Response from TTS synthesis.
 *
 * <p>This class encapsulates the result of a text-to-speech synthesis operation,
 * including the audio data and metadata about the synthesis.
 *
 * <p>The response can contain either raw audio bytes or a URL to the audio file,
 * depending on the TTS provider and configuration.
 */
public class TTSResponse {

    /** Raw audio data bytes. */
    private final byte[] audioData;

    /** URL to the audio file (if provided instead of raw data). */
    private final String audioUrl;

    /** Audio format (e.g., "wav", "mp3"). */
    private final String format;

    /** Audio sample rate in Hz. */
    private final Integer sampleRate;

    /** Audio duration in milliseconds. */
    private final Long durationMs;

    /** Request ID for tracking. */
    private final String requestId;

    private TTSResponse(Builder builder) {
        this.audioData = builder.audioData;
        this.audioUrl = builder.audioUrl;
        this.format = builder.format;
        this.sampleRate = builder.sampleRate;
        this.durationMs = builder.durationMs;
        this.requestId = builder.requestId;
    }

    /**
     * Gets the raw audio data.
     *
     * @return audio data bytes, or null if only URL is available
     */
    public byte[] getAudioData() {
        return audioData;
    }

    /**
     * Gets the audio URL.
     *
     * @return URL to audio file, or null if only raw data is available
     */
    public String getAudioUrl() {
        return audioUrl;
    }

    /**
     * Gets the audio format.
     *
     * @return audio format (e.g., "wav", "mp3")
     */
    public String getFormat() {
        return format;
    }

    /**
     * Gets the audio sample rate.
     *
     * @return sample rate in Hz
     */
    public Integer getSampleRate() {
        return sampleRate;
    }

    /**
     * Gets the audio duration.
     *
     * @return duration in milliseconds
     */
    public Long getDurationMs() {
        return durationMs;
    }

    /**
     * Gets the request ID.
     *
     * @return request ID for tracking
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * Converts this response to an AudioBlock for use in Msg.
     *
     * <p>If audio data is available, it will be encoded as Base64.
     * If only URL is available, it will be used directly.
     *
     * @return an AudioBlock containing the audio content
     * @throws IllegalStateException if neither audio data nor URL is available
     */
    public AudioBlock toAudioBlock() {
        if (audioData != null && audioData.length > 0) {
            String mediaType = getMediaType();
            String base64Data = Base64.getEncoder().encodeToString(audioData);
            return AudioBlock.builder()
                    .source(Base64Source.builder().mediaType(mediaType).data(base64Data).build())
                    .build();
        } else if (audioUrl != null && !audioUrl.isEmpty()) {
            return AudioBlock.builder().source(new URLSource(audioUrl)).build();
        } else {
            throw new IllegalStateException("No audio data or URL available");
        }
    }

    /**
     * Gets the MIME type based on the audio format.
     */
    private String getMediaType() {
        if (format == null) {
            return "audio/wav";
        }
        return switch (format.toLowerCase()) {
            case "mp3" -> "audio/mpeg";
            case "ogg" -> "audio/ogg";
            case "pcm" -> "audio/pcm";
            case "wav" -> "audio/wav";
            default -> "audio/" + format;
        };
    }

    /**
     * Creates a new builder for TTSResponse.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing TTSResponse instances.
     */
    public static class Builder {
        private byte[] audioData;
        private String audioUrl;
        private String format;
        private Integer sampleRate;
        private Long durationMs;
        private String requestId;

        /**
         * Sets the raw audio data.
         *
         * @param audioData audio bytes
         * @return this builder
         */
        public Builder audioData(byte[] audioData) {
            this.audioData = audioData;
            return this;
        }

        /**
         * Sets the audio URL.
         *
         * @param audioUrl URL to audio file
         * @return this builder
         */
        public Builder audioUrl(String audioUrl) {
            this.audioUrl = audioUrl;
            return this;
        }

        /**
         * Sets the audio format.
         *
         * @param format audio format
         * @return this builder
         */
        public Builder format(String format) {
            this.format = format;
            return this;
        }

        /**
         * Sets the sample rate.
         *
         * @param sampleRate sample rate in Hz
         * @return this builder
         */
        public Builder sampleRate(Integer sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Sets the duration.
         *
         * @param durationMs duration in milliseconds
         * @return this builder
         */
        public Builder durationMs(Long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        /**
         * Sets the request ID.
         *
         * @param requestId request ID
         * @return this builder
         */
        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        /**
         * Builds the TTSResponse instance.
         *
         * @return a new TTSResponse
         */
        public TTSResponse build() {
            return new TTSResponse(this);
        }
    }
}
