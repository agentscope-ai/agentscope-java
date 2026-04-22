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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DashScope TTS API request DTO.
 *
 * <p>Request format:
 * <pre>{@code
 * {
 *   "model": "qwen3-tts-flash",
 *   "input": {
 *     "text": "要合成的文本",
 *     "voice": "Cherry",
 *     "language_type": "Chinese"
 *   },
 *   "parameters": {
 *     "sample_rate": 24000,
 *     "format": "wav",
 *     "rate": 1.0,
 *     "volume": 50,
 *     "pitch": 1.0
 *   }
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashScopeTTSRequest {

    @JsonProperty("model")
    private final String model;

    @JsonProperty("input")
    private final TTSInput input;

    @JsonProperty("parameters")
    private final TTSParameters parameters;

    @JsonCreator
    private DashScopeTTSRequest(
            @JsonProperty("model") String model,
            @JsonProperty("input") TTSInput input,
            @JsonProperty("parameters") TTSParameters parameters) {
        this.model = model;
        this.input = input;
        this.parameters = parameters;
    }

    private DashScopeTTSRequest(Builder builder) {
        this(builder.model, builder.input, builder.parameters);
    }

    public String getModel() {
        return model;
    }

    public TTSInput getInput() {
        return input;
    }

    public TTSParameters getParameters() {
        return parameters;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String model;
        private TTSInput input;
        private TTSParameters parameters;

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder input(TTSInput input) {
            this.input = input;
            return this;
        }

        public Builder parameters(TTSParameters parameters) {
            this.parameters = parameters;
            return this;
        }

        public DashScopeTTSRequest build() {
            return new DashScopeTTSRequest(this);
        }
    }

    /**
     * TTS input containing text, voice, and language.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TTSInput {
        @JsonProperty("text")
        private final String text;

        @JsonProperty("voice")
        private final String voice;

        @JsonProperty("language_type")
        private final String languageType;

        @JsonCreator
        private TTSInput(
                @JsonProperty("text") String text,
                @JsonProperty("voice") String voice,
                @JsonProperty("language_type") String languageType) {
            this.text = text;
            this.voice = voice;
            this.languageType = languageType;
        }

        private TTSInput(Builder builder) {
            this(builder.text, builder.voice, builder.languageType);
        }

        public String getText() {
            return text;
        }

        public String getVoice() {
            return voice;
        }

        public String getLanguageType() {
            return languageType;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String text;
            private String voice;
            private String languageType;

            public Builder text(String text) {
                this.text = text;
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

            public TTSInput build() {
                return new TTSInput(this);
            }
        }
    }

    /**
     * TTS parameters for audio format settings.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TTSParameters {
        @JsonProperty("sample_rate")
        private final Integer sampleRate;

        @JsonProperty("format")
        private final String format;

        @JsonProperty("rate")
        private final Double rate;

        @JsonProperty("volume")
        private final Integer volume;

        @JsonProperty("pitch")
        private final Double pitch;

        @JsonCreator
        private TTSParameters(
                @JsonProperty("sample_rate") Integer sampleRate,
                @JsonProperty("format") String format,
                @JsonProperty("rate") Double rate,
                @JsonProperty("volume") Integer volume,
                @JsonProperty("pitch") Double pitch) {
            this.sampleRate = sampleRate;
            this.format = format;
            this.rate = rate;
            this.volume = volume;
            this.pitch = pitch;
        }

        private TTSParameters(Builder builder) {
            this(builder.sampleRate, builder.format, builder.rate, builder.volume, builder.pitch);
        }

        public Integer getSampleRate() {
            return sampleRate;
        }

        public String getFormat() {
            return format;
        }

        public Double getRate() {
            return rate;
        }

        public Integer getVolume() {
            return volume;
        }

        public Double getPitch() {
            return pitch;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private Integer sampleRate;
            private String format;
            private Double rate;
            private Integer volume;
            private Double pitch;

            public Builder sampleRate(Integer sampleRate) {
                this.sampleRate = sampleRate;
                return this;
            }

            public Builder format(String format) {
                this.format = format;
                return this;
            }

            public Builder rate(Double rate) {
                this.rate = rate;
                return this;
            }

            public Builder volume(Integer volume) {
                this.volume = volume;
                return this;
            }

            public Builder pitch(Double pitch) {
                this.pitch = pitch;
                return this;
            }

            public TTSParameters build() {
                return new TTSParameters(this);
            }
        }
    }
}
