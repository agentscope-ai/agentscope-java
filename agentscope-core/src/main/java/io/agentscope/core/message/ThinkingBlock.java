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
import java.util.HashMap;
import java.util.Map;

/**
 * Represents reasoning or thinking content in a message.
 *
 * <p>This content block is used to capture the internal reasoning process of an agent before
 * taking action. It provides transparency into how the agent arrived at its decisions or tool
 * choices.
 *
 * <p>Thinking blocks are particularly useful in ReAct agents and other reasoning-intensive systems
 * where understanding the agent's thought process is valuable for debugging and analysis.
 *
 * <p>The optional metadata field can store additional reasoning information such as OpenRouter's
 * reasoning_details (reasoning.text, reasoning.encrypted, reasoning.summary) that need to be
 * preserved and restored when formatting messages back to the API.
 */
public final class ThinkingBlock extends ContentBlock {

    /** Metadata key for storing OpenRouter/Gemini reasoning details list. */
    public static final String METADATA_REASONING_DETAILS = "reasoningDetails";

    private final String thinking;
    private final Map<String, Object> metadata;

    /**
     * Creates a new thinking block for JSON deserialization.
     *
     * @param text The thinking content (null will be converted to empty string)
     * @param metadata Optional metadata for storing additional reasoning information
     */
    @JsonCreator
    private ThinkingBlock(
            @JsonProperty("thinking") String text,
            @JsonProperty("metadata") Map<String, Object> metadata) {
        this.thinking = text != null ? text : "";
        this.metadata = metadata != null ? new HashMap<>(metadata) : null;
    }

    /**
     * Gets the thinking/reasoning content of this block.
     *
     * @return The thinking content
     */
    public String getThinking() {
        return thinking;
    }

    /**
     * Gets the metadata associated with this thinking block.
     *
     * <p>Metadata can contain additional reasoning information such as:
     *
     * <ul>
     *   <li>{@link #METADATA_REASONING_DETAILS} - List of OpenAIReasoningDetail objects from
     *       OpenRouter/Gemini
     * </ul>
     *
     * @return The metadata map, or null if no metadata is set
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Creates a new builder for constructing ThinkingBlock instances.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for constructing ThinkingBlock instances. */
    public static class Builder {

        private String thinking;
        private Map<String, Object> metadata;

        /**
         * Sets the thinking content for the block.
         *
         * @param thinking The thinking content
         * @return This builder for chaining
         */
        public Builder thinking(String thinking) {
            this.thinking = thinking;
            return this;
        }

        /**
         * Sets the metadata for the block.
         *
         * <p>Metadata can store additional reasoning information that needs to be preserved, such
         * as OpenRouter's reasoning_details.
         *
         * @param metadata The metadata map
         * @return This builder for chaining
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Builds a new ThinkingBlock with the configured thinking content and metadata.
         *
         * @return A new ThinkingBlock instance (null thinking will be converted to empty string)
         */
        public ThinkingBlock build() {
            return new ThinkingBlock(thinking != null ? thinking : "", metadata);
        }
    }
}
