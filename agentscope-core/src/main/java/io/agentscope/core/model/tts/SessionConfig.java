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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Session configuration for DashScope Realtime TTS.
 *
 * <p>This class represents the session configuration sent to the TTS service
 * via WebSocket, including voice settings, format, and mode.
 */
public class SessionConfig {

    @JsonProperty("mode")
    private final String mode;

    @JsonProperty("voice")
    private final String voice;

    @JsonProperty("language_type")
    private final String languageType;

    @JsonProperty("response_format")
    private final String responseFormat;

    @JsonProperty("sample_rate")
    private final int sampleRate;

    @JsonCreator
    private SessionConfig(
            @JsonProperty("mode") String mode,
            @JsonProperty("voice") String voice,
            @JsonProperty("language_type") String languageType,
            @JsonProperty("response_format") String responseFormat,
            @JsonProperty("sample_rate") int sampleRate) {
        this.mode = mode;
        this.voice = voice;
        this.languageType = languageType;
        this.responseFormat = responseFormat;
        this.sampleRate = sampleRate;
    }

    private SessionConfig(Builder builder) {
        this(
                builder.mode,
                builder.voice,
                builder.languageType,
                builder.responseFormat,
                builder.sampleRate);
    }

    public String getMode() {
        return mode;
    }

    public String getVoice() {
        return voice;
    }

    public String getLanguageType() {
        return languageType;
    }

    public String getResponseFormat() {
        return responseFormat;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String mode;
        private String voice;
        private String languageType;
        private String responseFormat;
        private int sampleRate;

        public Builder mode(String mode) {
            this.mode = mode;
            return this;
        }

        public Builder voice(String voice) {
            this.voice = voice;
            return this;
        }

        public Builder languageType(String languageType) {
            this.languageType = languageType;
            return this;
        }

        public Builder responseFormat(String responseFormat) {
            this.responseFormat = responseFormat;
            return this;
        }

        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        public SessionConfig build() {
            return new SessionConfig(this);
        }
    }
}
