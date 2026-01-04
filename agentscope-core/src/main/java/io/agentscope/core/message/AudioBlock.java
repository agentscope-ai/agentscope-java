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
 * Represents audio content in a message.
 *
 * <p>This content block supports audio from two sources:
 * <ul>
 *   <li>URL source - audio files accessible via HTTP/HTTPS URLs or local file URLs</li>
 *   <li>Base64 source - audio encoded as Base64 strings with MIME type</li>
 * </ul>
 *
 * <p>Audio blocks enable multimodal AI interactions where agents need to process
 * or generate audio content such as speech, music, sound effects, or other
 * auditory information.
 */
public final class AudioBlock extends ContentBlock {

    private final Source source;

    /**
     * Creates a new audio block for JSON deserialization.
     *
     * @param source The audio source (URL or Base64)
     * @throws NullPointerException if source is null
     */
    @JsonCreator
    public AudioBlock(@JsonProperty("source") Source source) {
        this.source = Objects.requireNonNull(source, "source cannot be null");
    }

    /**
     * Gets the source of this audio content.
     *
     * @return The audio source containing URL or Base64 data
     */
    public Source getSource() {
        return source;
    }

    /**
     * Creates a new builder for constructing AudioBlock instances.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing AudioBlock instances.
     */
    public static class Builder {

        private Source source;

        /**
         * Sets the source for the audio content.
         *
         * @param source The audio source (URL or Base64)
         * @return This builder for chaining
         */
        public Builder source(Source source) {
            this.source = source;
            return this;
        }

        /**
         * Builds a new AudioBlock with the configured source.
         *
         * @return A new AudioBlock instance
         * @throws NullPointerException if source is null
         */
        public AudioBlock build() {
            return new AudioBlock(source);
        }
    }
}
