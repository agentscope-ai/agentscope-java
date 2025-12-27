/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Represents reasoning or thinking content in a message.
 *
 * <p>
 * This content block is used to capture the internal reasoning process of an
 * agent before
 * taking action. It provides transparency into how the agent arrived at its
 * decisions or tool
 * choices.
 *
 * <p>
 * Thinking blocks are particularly useful in ReAct agents and other
 * reasoning-intensive systems
 * where understanding the agent's thought process is valuable for debugging and
 * analysis.
 *
 * <p>
 * <b>Model-Specific Metadata:</b> Different models may attach additional
 * metadata to thinking
 * blocks:
 *
 * <ul>
 * <li>Gemini: Uses {@link #METADATA_THOUGHT_SIGNATURE} to store thought
 * signatures for
 * multi-turn context preservation
 * <li>Other models may define their own metadata keys as needed
 * </ul>
 */
public final class ThinkingBlock extends ContentBlock {

    /**
     * Metadata key for Gemini thought signature.
     *
     * <p>
     * Gemini thinking models return encrypted thought signatures that must be
     * passed back in
     * subsequent requests to maintain reasoning context across turns. This is
     * particularly
     * important for function calling scenarios.
     *
     * @see <a href=
     *      "https://ai.google.dev/gemini-api/docs/thought-signatures">Gemini
     *      Thought
     *      Signatures</a>
     */
    public static final String METADATA_THOUGHT_SIGNATURE = "thoughtSignature";

    private final String thinking;
    private final Map<String, Object> metadata;

    /**
     * Creates a new thinking block for JSON deserialization.
     *
     * @param text     The thinking content (null will be converted to empty string)
     * @param metadata Optional metadata map for model-specific data
     */
    @JsonCreator
    private ThinkingBlock(
            @JsonProperty("thinking") String text,
            @JsonProperty("metadata") Map<String, Object> metadata) {
        this.thinking = text != null ? text : "";
        this.metadata = metadata;
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
     * Gets the metadata map containing model-specific data.
     *
     * <p>
     * For Gemini models, this may contain {@link #METADATA_THOUGHT_SIGNATURE}.
     *
     * @return The metadata map, or null if no metadata is present
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Convenience method to get the Gemini thought signature from metadata.
     *
     * @return The thought signature if present, null otherwise
     */
    public String getSignature() {
        if (metadata == null) {
            return null;
        }
        Object sig = metadata.get(METADATA_THOUGHT_SIGNATURE);
        return sig instanceof String ? (String) sig : null;
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
         * Sets the metadata map for model-specific data.
         *
         * @param metadata The metadata map
         * @return This builder for chaining
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Convenience method to set the Gemini thought signature.
         *
         * <p>
         * This creates or updates the metadata map with the signature.
         *
         * @param signature The thought signature
         * @return This builder for chaining
         */
        public Builder signature(String signature) {
            if (signature != null) {
                if (this.metadata == null) {
                    this.metadata = new java.util.HashMap<>();
                }
                this.metadata.put(METADATA_THOUGHT_SIGNATURE, signature);
            }
            return this;
        }

        /**
         * Builds a new ThinkingBlock with the configured thinking content.
         *
         * @return A new ThinkingBlock instance (null thinking will be converted to
         *         empty string)
         */
        public ThinkingBlock build() {
            return new ThinkingBlock(thinking != null ? thinking : "", metadata);
        }
    }
}
