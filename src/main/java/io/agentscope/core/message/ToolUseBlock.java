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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
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
public class ToolUseBlock extends ContentBlock {

    @JsonIgnore private final ContentBlockType type = ContentBlockType.TOOL_USE;
    private final String id;
    private final String name;
    private final Map<String, Object> input;
    private final String content; // Raw content for streaming tool calls

    /**
     * Creates a new tool use block for JSON deserialization.
     *
     * @param id Unique identifier for this tool call
     * @param name Name of the tool to execute
     * @param input Input parameters for the tool
     */
    @JsonCreator
    public ToolUseBlock(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("input") Map<String, Object> input) {
        this.id = id;
        this.name = name;
        this.input = input;
        this.content = null;
    }

    /**
     * Creates a new tool use block with raw content for streaming.
     *
     * @param id Unique identifier for this tool call
     * @param name Name of the tool to execute
     * @param input Input parameters for the tool
     * @param content Raw content for streaming tool calls
     */
    public ToolUseBlock(String id, String name, Map<String, Object> input, String content) {
        this.id = id;
        this.name = name;
        this.input = input;
        this.content = content;
    }

    @Override
    public ContentBlockType getType() {
        return type;
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
         * Builds a new ToolUseBlock with the configured properties.
         *
         * @return A new ToolUseBlock instance
         */
        public ToolUseBlock build() {
            if (content != null) {
                return new ToolUseBlock(id, name, input, content);
            }
            return new ToolUseBlock(id, name, input);
        }
    }
}
