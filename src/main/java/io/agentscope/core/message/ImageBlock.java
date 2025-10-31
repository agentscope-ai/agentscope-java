/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents image content in a message with URL or Base64 source.
 *
 * <p>This content block supports images from two sources:
 * <ul>
 *   <li>URL source - images accessible via HTTP/HTTPS URLs or local file URLs</li>
 *   <li>Base64 source - images encoded as Base64 strings with MIME type</li>
 * </ul>
 *
 * <p>Image blocks are essential for multimodal AI interactions where agents
 * need to process visual information from images, diagrams, screenshots,
 * or other visual content.
 */
public class ImageBlock extends ContentBlock {

    private final Source source;

    /**
     * Creates a new image block for JSON deserialization.
     *
     * @param source The image source (URL or Base64)
     */
    @JsonCreator
    public ImageBlock(@JsonProperty("source") Source source) {
        this.source = source;
    }

    /**
     * Gets the source of this image.
     *
     * @return The image source containing URL or Base64 data
     */
    public Source getSource() {
        return source;
    }

    @Override
    public ContentBlockType getType() {
        return ContentBlockType.IMAGE;
    }

    /**
     * Creates a new builder for constructing ImageBlock instances.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing ImageBlock instances.
     */
    public static class Builder {

        private Source source;

        /**
         * Sets the source for the image.
         *
         * @param source The image source (URL or Base64)
         * @return This builder for chaining
         */
        public Builder source(Source source) {
            this.source = source;
            return this;
        }

        /**
         * Builds a new ImageBlock with the configured source.
         *
         * @return A new ImageBlock instance
         */
        public ImageBlock build() {
            return new ImageBlock(source);
        }
    }
}
