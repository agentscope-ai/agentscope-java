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
import java.util.Objects;

/**
 * Transcription content block for Live sessions.
 *
 * <p>Represents transcribed text from either:
 *
 * <ul>
 *   <li>User's speech input (ASR - Automatic Speech Recognition)
 *   <li>Model's audio output (TTS text)
 * </ul>
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * // Input transcription (what user said)
 * TranscriptionBlock inputTrans = TranscriptionBlock.input("Hello, how are you?");
 *
 * // Output transcription (what model said)
 * TranscriptionBlock outputTrans = TranscriptionBlock.output("I'm doing well, thank you!");
 *
 * // Partial transcription (streaming)
 * TranscriptionBlock partial = TranscriptionBlock.builder()
 *     .text("Hello")
 *     .type(TranscriptionType.INPUT)
 *     .partial(true)
 *     .build();
 * }</pre>
 *
 * @see TranscriptionType
 */
public final class TranscriptionBlock extends ContentBlock {

    private final String text;
    private final TranscriptionType type;
    private final boolean partial;
    private final String language;
    private final Float confidence;

    /**
     * Creates a new TranscriptionBlock.
     *
     * @param text the transcribed text
     * @param type the transcription type (INPUT or OUTPUT)
     * @param partial whether this is a partial (streaming) transcription
     * @param language detected language code (e.g., "en", "zh")
     * @param confidence confidence score (0.0 to 1.0)
     */
    @JsonCreator
    public TranscriptionBlock(
            @JsonProperty("text") String text,
            @JsonProperty("type") TranscriptionType type,
            @JsonProperty("partial") boolean partial,
            @JsonProperty("language") String language,
            @JsonProperty("confidence") Float confidence) {
        this.text = Objects.requireNonNull(text, "text cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.partial = partial;
        this.language = language;
        this.confidence = confidence;
    }

    /**
     * Gets the transcribed text.
     *
     * @return the text
     */
    public String getText() {
        return text;
    }

    /**
     * Gets the transcription type.
     *
     * @return INPUT or OUTPUT
     */
    public TranscriptionType getType() {
        return type;
    }

    /**
     * Checks if this is a partial (streaming) transcription.
     *
     * @return true if partial, false if final
     */
    public boolean isPartial() {
        return partial;
    }

    /**
     * Gets the detected language code.
     *
     * @return language code or null
     */
    public String getLanguage() {
        return language;
    }

    /**
     * Gets the confidence score.
     *
     * @return confidence (0.0 to 1.0) or null
     */
    public Float getConfidence() {
        return confidence;
    }

    // ==================== Static Factory Methods ====================

    /**
     * Creates an input transcription (user's speech).
     *
     * @param text the transcribed text
     * @return a new TranscriptionBlock
     */
    public static TranscriptionBlock input(String text) {
        return new TranscriptionBlock(text, TranscriptionType.INPUT, false, null, null);
    }

    /**
     * Creates an output transcription (model's speech).
     *
     * @param text the transcribed text
     * @return a new TranscriptionBlock
     */
    public static TranscriptionBlock output(String text) {
        return new TranscriptionBlock(text, TranscriptionType.OUTPUT, false, null, null);
    }

    /**
     * Creates a partial input transcription (streaming).
     *
     * @param text the partial transcribed text
     * @return a new TranscriptionBlock
     */
    public static TranscriptionBlock inputPartial(String text) {
        return new TranscriptionBlock(text, TranscriptionType.INPUT, true, null, null);
    }

    /**
     * Creates a partial output transcription (streaming).
     *
     * @param text the partial transcribed text
     * @return a new TranscriptionBlock
     */
    public static TranscriptionBlock outputPartial(String text) {
        return new TranscriptionBlock(text, TranscriptionType.OUTPUT, true, null, null);
    }

    /**
     * Creates a builder for custom transcription blocks.
     *
     * @return a new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for TranscriptionBlock. */
    public static class Builder {
        private String text;
        private TranscriptionType type = TranscriptionType.INPUT;
        private boolean partial = false;
        private String language;
        private Float confidence;

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder type(TranscriptionType type) {
            this.type = type;
            return this;
        }

        public Builder partial(boolean partial) {
            this.partial = partial;
            return this;
        }

        public Builder language(String language) {
            this.language = language;
            return this;
        }

        public Builder confidence(Float confidence) {
            this.confidence = confidence;
            return this;
        }

        public TranscriptionBlock build() {
            return new TranscriptionBlock(text, type, partial, language, confidence);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TranscriptionBlock that = (TranscriptionBlock) o;
        return partial == that.partial
                && Objects.equals(text, that.text)
                && type == that.type
                && Objects.equals(language, that.language)
                && Objects.equals(confidence, that.confidence);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, type, partial, language, confidence);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TranscriptionBlock{");
        sb.append("text='").append(text).append('\'');
        sb.append(", type=").append(type);
        sb.append(", partial=").append(partial);
        if (language != null) {
            sb.append(", language='").append(language).append('\'');
        }
        if (confidence != null) {
            sb.append(", confidence=").append(confidence);
        }
        sb.append('}');
        return sb.toString();
    }
}
