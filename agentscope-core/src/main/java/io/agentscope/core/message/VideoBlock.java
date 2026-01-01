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

/**
 * Represents video content in a message.
 *
 * <p>This content block supports video from two sources:
 * <ul>
 *   <li>URL source - video files accessible via HTTP/HTTPS URLs or local file URLs</li>
 *   <li>Base64 source - video encoded as Base64 strings with MIME type</li>
 * </ul>
 *
 * <p>Video blocks enable advanced multimodal AI interactions where agents need to process
 * or analyze video content such as presentations, tutorials, surveillance footage,
 * or other visual media that includes motion and temporal elements.
 */
public final class VideoBlock extends ContentBlock {

    private final Source source;

    /**
     * Creates a new video block for JSON deserialization.
     *
     * @param source The video source (URL or Base64)
     */
    @JsonCreator
    public VideoBlock(@JsonProperty("source") Source source) {
        this.source = source;
    }

    /**
     * Gets the source of this video content.
     *
     * @return The video source containing URL or Base64 data
     */
    public Source getSource() {
        return source;
    }

    /**
     * Creates a new builder for constructing VideoBlock instances.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing VideoBlock instances.
     */
    public static class Builder {

        private Source source;

        /**
         * Sets the source for the video content.
         *
         * @param source The video source (URL or Base64)
         * @return This builder for chaining
         */
        public Builder source(Source source) {
            this.source = source;
            return this;
        }

        /**
         * Builds a new VideoBlock with the configured source.
         *
         * @return A new VideoBlock instance
         */
        public VideoBlock build() {
            return new VideoBlock(source);
        }
    }
}
