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
 * Represents plain text content in a message.
 *
 * <p>This is the most basic content block type, containing simple text content.
 * Text blocks are commonly used for user messages, assistant responses,
 * and any other textual communication.
 *
 * <p>The text content can be empty but never null. The toString() method
 * returns the text content for convenience.
 */
public final class TextBlock extends ContentBlock {

    private final String text;

    /**
     * Creates a new text block for JSON deserialization.
     *
     * @param text The text content (null will be converted to empty string)
     */
    @JsonCreator
    private TextBlock(@JsonProperty("text") String text) {
        this.text = text != null ? text : "";
    }

    /**
     * Gets the text content of this block.
     *
     * @return The text content
     */
    public String getText() {
        return text;
    }

    @Override
    public String toString() {
        return text;
    }

    /**
     * Creates a new builder for constructing TextBlock instances.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing TextBlock instances.
     */
    public static class Builder {

        private String text;

        /**
         * Sets the text content for the block.
         *
         * @param text The text content
         * @return This builder for chaining
         */
        public Builder text(String text) {
            this.text = text;
            return this;
        }

        /**
         * Builds a new TextBlock with the configured text.
         *
         * @return A new TextBlock instance (null text will be converted to empty string)
         */
        public TextBlock build() {
            return new TextBlock(text != null ? text : "");
        }
    }
}
