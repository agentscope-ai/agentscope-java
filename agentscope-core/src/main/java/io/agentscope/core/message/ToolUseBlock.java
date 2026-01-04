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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a tool use request within a message.
 *
 * <p>This content block is used when an agent requests to execute a tool.
 * It contains the tool's unique identifier, name, input parameters, and optionally
 * the raw content for streaming tool calls.
 *
 * <p>The tool input is stored as a generic map of string keys to object values,
 * allowing for flexible parameter passing to different tool implementations.
 */
public final class ToolUseBlock extends ContentBlock {

    /** Metadata key for Gemini thought signature (byte[] value). */
    public static final String METADATA_THOUGHT_SIGNATURE = "thoughtSignature";

    private final String id;
    private final String name;
    private final Map<String, Object> input;
    private final String content; // Raw content for streaming tool calls
    private final Map<String, Object> metadata; // Provider-specific metadata

    /**
     * Creates a new tool use block for JSON deserialization.
     *
     * @param id Unique identifier for this tool call
     * @param name Name of the tool to execute
     * @param input Input parameters for the tool (will be defensively copied)
     * @param metadata Provider-specific metadata (will be defensively copied)
     */
    @JsonCreator
    public ToolUseBlock(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("input") Map<String, Object> input,
            @JsonProperty("metadata") Map<String, Object> metadata) {
        this(id, name, input, null, metadata);
    }

    /**
     * Creates a new tool use block without metadata (convenience constructor).
     *
     * @param id Unique identifier for this tool call
     * @param name Name of the tool to execute
     * @param input Input parameters for the tool (will be defensively copied)
     */
    public ToolUseBlock(String id, String name, Map<String, Object> input) {
        this(id, name, input, null, null);
    }

    /**
     * Creates a new tool use block with raw content for streaming.
     *
     * @param id Unique identifier for this tool call
     * @param name Name of the tool to execute
     * @param input Input parameters for the tool (will be defensively copied)
     * @param content Raw content for streaming tool calls
     * @param metadata Provider-specific metadata (will be defensively copied)
     */
    public ToolUseBlock(
            String id,
            String name,
            Map<String, Object> input,
            String content,
            Map<String, Object> metadata) {
        this.id = id;
        this.name = name;
        // Defensive copy to prevent external modifications
        this.input =
                input == null
                        ? Collections.emptyMap()
                        : Collections.unmodifiableMap(new HashMap<>(input));
        this.content = content;
        this.metadata =
                metadata == null
                        ? Collections.emptyMap()
                        : Collections.unmodifiableMap(new HashMap<>(metadata));
    }

    /**
     * Gets the unique identifier of this tool call.
     *
     * @return The tool call ID
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the name of the tool to execute.
     *
     * @return The tool name
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the input parameters for the tool.
     *
     * @return The tool input parameters map
     */
    public Map<String, Object> getInput() {
        return input;
    }

    /**
     * Gets the raw content for streaming tool calls.
     *
     * @return The raw content, or null if not set
     */
    public String getContent() {
        return content;
    }

    /**
     * Gets the provider-specific metadata.
     *
     * <p>For Gemini, this may contain the thought signature under the key
     * {@link #METADATA_THOUGHT_SIGNATURE}.
     *
     * @return The metadata map, or an empty map if not set
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Creates a new builder for constructing a ToolUseBlock.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing ToolUseBlock instances.
     */
    public static class Builder {
        private String id;
        private String name;
        private Map<String, Object> input;
        private String content;
        private Map<String, Object> metadata;

        /**
         * Sets the unique identifier for the tool call.
         *
         * @param id The tool call ID
         * @return This builder for chaining
         */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * Sets the name of the tool to execute.
         *
         * @param name The tool name
         * @return This builder for chaining
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the input parameters for the tool.
         *
         * @param input The tool input parameters map
         * @return This builder for chaining
         */
        public Builder input(Map<String, Object> input) {
            this.input = input;
            return this;
        }

        /**
         * Sets the raw content for streaming tool calls.
         *
         * @param content The raw content for streaming
         * @return This builder for chaining
         */
        public Builder content(String content) {
            this.content = content;
            return this;
        }

        /**
         * Sets the provider-specific metadata.
         *
         * <p>For Gemini, use {@link ToolUseBlock#METADATA_THOUGHT_SIGNATURE} as the key
         * to store thought signatures.
         *
         * @param metadata The metadata map
         * @return This builder for chaining
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Builds a new ToolUseBlock with the configured properties.
         *
         * @return A new ToolUseBlock instance
         */
        public ToolUseBlock build() {
            return new ToolUseBlock(id, name, input, content, metadata);
        }
    }
}
