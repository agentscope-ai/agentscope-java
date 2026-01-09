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
package io.agentscope.core.live.config;

import java.util.Objects;

/**
 * Live session configuration (simplified version).
 *
 * <p>Design principles:
 *
 * <ul>
 *   <li>Only includes user-configurable common parameters
 *   <li>Audio formats are determined by Formatter, not exposed to users
 *   <li>Tool definitions are obtained from Agent's Toolkit
 *   <li>Provider-specific configurations are placed in respective LiveModel Builders
 * </ul>
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * LiveConfig config = LiveConfig.builder()
 *     .voice("alloy")
 *     .instructions("You are a friendly voice assistant.")
 *     .enableInputTranscription(true)
 *     .enableOutputTranscription(true)
 *     .autoReconnect(true)
 *     .build();
 * }</pre>
 */
public final class LiveConfig {

    /** Voice name (provider-specific) */
    private final String voice;

    /** System instructions */
    private final String instructions;

    /** Generation parameter configuration */
    private final GenerationConfig generationConfig;

    /** Whether to enable input transcription */
    private final boolean enableInputTranscription;

    /** Whether to enable output transcription */
    private final boolean enableOutputTranscription;

    /** Whether to auto-reconnect (only Gemini/Doubao support native resumption) */
    private final boolean autoReconnect;

    /** Reconnection configuration */
    private final ReconnectConfig reconnectConfig;

    private LiveConfig(Builder builder) {
        this.voice = builder.voice;
        this.instructions = builder.instructions;
        this.generationConfig = builder.generationConfig;
        this.enableInputTranscription = builder.enableInputTranscription;
        this.enableOutputTranscription = builder.enableOutputTranscription;
        this.autoReconnect = builder.autoReconnect;
        this.reconnectConfig =
                builder.reconnectConfig != null
                        ? builder.reconnectConfig
                        : ReconnectConfig.defaults();
    }

    public String getVoice() {
        return voice;
    }

    public String getInstructions() {
        return instructions;
    }

    public GenerationConfig getGenerationConfig() {
        return generationConfig;
    }

    public boolean isEnableInputTranscription() {
        return enableInputTranscription;
    }

    public boolean isEnableOutputTranscription() {
        return enableOutputTranscription;
    }

    public boolean isAutoReconnect() {
        return autoReconnect;
    }

    public ReconnectConfig getReconnectConfig() {
        return reconnectConfig;
    }

    public static LiveConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .voice(voice)
                .instructions(instructions)
                .generationConfig(generationConfig)
                .enableInputTranscription(enableInputTranscription)
                .enableOutputTranscription(enableOutputTranscription)
                .autoReconnect(autoReconnect)
                .reconnectConfig(reconnectConfig);
    }

    public static class Builder {
        private String voice;
        private String instructions;
        private GenerationConfig generationConfig;
        private boolean enableInputTranscription = true;
        private boolean enableOutputTranscription = true;
        private boolean autoReconnect = true;
        private ReconnectConfig reconnectConfig;

        public Builder voice(String voice) {
            this.voice = voice;
            return this;
        }

        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        public Builder generationConfig(GenerationConfig config) {
            this.generationConfig = config;
            return this;
        }

        public Builder enableInputTranscription(boolean enable) {
            this.enableInputTranscription = enable;
            return this;
        }

        public Builder enableOutputTranscription(boolean enable) {
            this.enableOutputTranscription = enable;
            return this;
        }

        public Builder autoReconnect(boolean autoReconnect) {
            this.autoReconnect = autoReconnect;
            return this;
        }

        public Builder reconnectConfig(ReconnectConfig config) {
            this.reconnectConfig = config;
            return this;
        }

        public LiveConfig build() {
            return new LiveConfig(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LiveConfig that = (LiveConfig) o;
        return enableInputTranscription == that.enableInputTranscription
                && enableOutputTranscription == that.enableOutputTranscription
                && autoReconnect == that.autoReconnect
                && Objects.equals(voice, that.voice)
                && Objects.equals(instructions, that.instructions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                voice,
                instructions,
                enableInputTranscription,
                enableOutputTranscription,
                autoReconnect);
    }
}
