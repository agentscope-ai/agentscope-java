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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

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
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class TextBlock extends ContentBlock {

    private final String text;
    private final List<Citation> citations;

    /**
     * Provider block index used to preserve text-block boundaries during aggregation.
     * This field is internal to the accumulator pipeline and is not serialized.
     *
     * @hidden
     */
    @JsonIgnore private final Long providerBlockIndex;

    /**
     * Creates a new text block for JSON deserialization.
     *
     * @param text The text content (null will be converted to empty string)
     * @param citations Optional citations supporting this text block
     */
    @JsonCreator
    private TextBlock(
            @JsonProperty("text") String text,
            @JsonProperty("citations") List<Citation> citations) {
        this.text = text != null ? text : "";
        this.citations = citations == null || citations.isEmpty() ? null : List.copyOf(citations);
        this.providerBlockIndex = null;
    }

    /**
     * Internal constructor used by the builder to set the provider block index.
     */
    private TextBlock(String text, List<Citation> citations, Long providerBlockIndex) {
        this.text = text != null ? text : "";
        this.citations = citations == null || citations.isEmpty() ? null : List.copyOf(citations);
        this.providerBlockIndex = providerBlockIndex;
    }

    /**
     * Gets the text content of this block.
     *
     * @return The text content
     */
    public String getText() {
        return text;
    }

    /**
     * Gets citations supporting the claim in this text block.
     *
     * @return immutable citation list, or an empty list when uncited
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<Citation> getCitations() {
        return citations != null ? citations : List.of();
    }

    /**
     * Gets the provider block index, used internally during aggregation.
     *
     * @hidden
     * @return the provider block index, or null when not set
     */
    @JsonIgnore
    public Long getProviderBlockIndex() {
        return providerBlockIndex;
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
        private List<Citation> citations;
        private Long providerBlockIndex;

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
         * Sets citations supporting this text block.
         *
         * @param citations citations attached to the complete text block
         * @return This builder for chaining
         */
        public Builder citations(List<Citation> citations) {
            this.citations = citations;
            return this;
        }

        /**
         * Sets the provider block index for aggregation.
         *
         * @param providerBlockIndex the provider block index
         * @return This builder for chaining
         * @hidden
         */
        public Builder providerBlockIndex(long providerBlockIndex) {
            this.providerBlockIndex = providerBlockIndex;
            return this;
        }

        /**
         * Builds a new TextBlock with the configured text.
         *
         * @return A new TextBlock instance (null text will be converted to empty string)
         */
        public TextBlock build() {
            return new TextBlock(text != null ? text : "", citations, providerBlockIndex);
        }
    }
}
