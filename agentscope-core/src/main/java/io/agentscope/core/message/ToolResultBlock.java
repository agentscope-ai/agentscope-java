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
import io.agentscope.core.tool.ToolSuspendException;
import java.util.List;
import java.util.Map;

/**
 * Represents the result of a tool execution.
 *
 * This class serves two purposes:
 * 1. As a return value from tool methods (id and name are null)
 * 2. As a ContentBlock in messages (id and name are required)
 *
 * Supports metadata for passing additional execution information.
 */
public final class ToolResultBlock extends ContentBlock {

    /** Metadata key indicating this result is suspended for external execution. */
    public static final String METADATA_SUSPENDED = "agentscope_suspended";

    private final String id;
    private final String name;
    private final List<ContentBlock> output;
    private final Map<String, Object> metadata;

    @JsonCreator
    public ToolResultBlock(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("output") List<ContentBlock> output,
            @JsonProperty("metadata") Map<String, Object> metadata) {
        this.id = id;
        this.name = name;
        this.output = output != null ? List.copyOf(output) : List.of();
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /**
     * Creates a tool result block with a single content block output.
     *
     * @param id Tool call ID
     * @param name Tool name
     * @param output Single content block as output
     */
    public ToolResultBlock(String id, String name, ContentBlock output) {
        this(id, name, List.of(output), null);
    }

    /**
     * Creates a tool result block with a list of content blocks as output.
     *
     * @param id Tool call ID
     * @param name Tool name
     * @param output List of content blocks as output
     */
    public ToolResultBlock(String id, String name, List<ContentBlock> output) {
        this(id, name, output, null);
    }

    /**
     * Gets the tool call ID.
     *
     * @return The tool call ID, or null if not set
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the tool name.
     *
     * @return The tool name, or null if not set
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the output content blocks.
     *
     * @return Immutable list of content blocks representing the tool output
     */
    public List<ContentBlock> getOutput() {
        return output;
    }

    /**
     * Gets the metadata associated with the tool result.
     *
     * @return Immutable metadata map, or empty map if not set
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Checks if this result is suspended for external execution.
     *
     * <p>A suspended result is created when a tool throws {@link ToolSuspendException},
     * indicating that the tool execution needs to be handled externally by the user.
     *
     * @return true if this result is suspended, false otherwise
     */
    public boolean isSuspended() {
        return Boolean.TRUE.equals(metadata.get(METADATA_SUSPENDED));
    }

    /**
     * Creates a suspended tool result from a ToolSuspendException.
     *
     * <p>This method is used by the framework to convert a {@link ToolSuspendException}
     * into a suspended result that will be returned to the user for external execution.
     *
     * @param toolUse The tool use block that triggered the exception
     * @param exception The exception thrown by the tool
     * @return A suspended ToolResultBlock
     */
    public static ToolResultBlock suspended(ToolUseBlock toolUse, ToolSuspendException exception) {
        String content =
                exception.getReason() != null
                        ? exception.getReason()
                        : "[Awaiting external execution]";
        return new ToolResultBlock(
                toolUse.getId(),
                toolUse.getName(),
                List.of(TextBlock.builder().text(content).build()),
                Map.of(METADATA_SUSPENDED, true));
    }

    /**
     * Creates a suspended tool result with default message.
     *
     * @param toolUse The tool use block that requires external execution
     * @return A suspended ToolResultBlock
     */
    public static ToolResultBlock suspended(ToolUseBlock toolUse) {
        return suspended(toolUse, new ToolSuspendException());
    }

    /**
     * Create a simple text result (for tool method return values).
     *
     * @param text Text content
     * @return ToolResultBlock with text output
     */
    public static ToolResultBlock text(String text) {
        return new ToolResultBlock(
                null, null, List.of(TextBlock.builder().text(text).build()), null);
    }

    /**
     * Create an error result (for tool method return values).
     *
     * @param errorMessage Error message
     * @return ToolResultBlock with error output
     */
    public static ToolResultBlock error(String errorMessage) {
        return new ToolResultBlock(
                null,
                null,
                List.of(TextBlock.builder().text("Error: " + errorMessage).build()),
                null);
    }

    /**
     * Create a result with output only (for tool method return values).
     *
     * @param output Content block output
     * @return ToolResultBlock with the given output
     */
    public static ToolResultBlock of(ContentBlock output) {
        return new ToolResultBlock(null, null, List.of(output), null);
    }

    /**
     * Create a result with output list only (for tool method return values).
     *
     * @param output List of content blocks
     * @return ToolResultBlock with the given output
     */
    public static ToolResultBlock of(List<ContentBlock> output) {
        return new ToolResultBlock(null, null, output, null);
    }

    /**
     * Create a result with output and metadata (for tool method return values).
     *
     * @param output Content block output
     * @param metadata Metadata map
     * @return ToolResultBlock with output and metadata
     */
    public static ToolResultBlock of(ContentBlock output, Map<String, Object> metadata) {
        return new ToolResultBlock(null, null, List.of(output), metadata);
    }

    /**
     * Create a result with output list and metadata (for tool method return values).
     *
     * @param output List of content blocks
     * @param metadata Metadata map
     * @return ToolResultBlock with output and metadata
     */
    public static ToolResultBlock of(List<ContentBlock> output, Map<String, Object> metadata) {
        return new ToolResultBlock(null, null, output, metadata);
    }

    /**
     * Create a result with id, name, and output (for message ContentBlock).
     *
     * @param id Tool call ID
     * @param name Tool name
     * @param output Content block output
     * @return ToolResultBlock for use in messages
     */
    public static ToolResultBlock of(String id, String name, ContentBlock output) {
        return new ToolResultBlock(id, name, List.of(output), null);
    }

    /**
     * Create a result with id, name, and output list (for message ContentBlock).
     *
     * @param id Tool call ID
     * @param name Tool name
     * @param output List of content blocks
     * @return ToolResultBlock for use in messages
     */
    public static ToolResultBlock of(String id, String name, List<ContentBlock> output) {
        return new ToolResultBlock(id, name, output, null);
    }

    /**
     * Create a result with all fields (for message ContentBlock with metadata).
     *
     * @param id Tool call ID
     * @param name Tool name
     * @param output Content block output
     * @param metadata Metadata map
     * @return ToolResultBlock with all fields
     */
    public static ToolResultBlock of(
            String id, String name, ContentBlock output, Map<String, Object> metadata) {
        return new ToolResultBlock(id, name, List.of(output), metadata);
    }

    /**
     * Create a result with all fields including output list (for message ContentBlock with
     * metadata).
     *
     * @param id Tool call ID
     * @param name Tool name
     * @param output List of content blocks
     * @param metadata Metadata map
     * @return ToolResultBlock with all fields
     */
    public static ToolResultBlock of(
            String id, String name, List<ContentBlock> output, Map<String, Object> metadata) {
        return new ToolResultBlock(id, name, output, metadata);
    }

    /**
     * Create a ToolResultBlock for use in messages by setting id and name.
     *
     * @param id Tool call ID
     * @param name Tool name
     * @return New ToolResultBlock with id and name set
     */
    public ToolResultBlock withIdAndName(String id, String name) {
        return new ToolResultBlock(id, name, this.output, this.metadata);
    }

    /**
     * Creates a new builder for constructing ToolResultBlock instances.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing ToolResultBlock instances.
     */
    public static class Builder {
        private String id;
        private String name;
        private List<ContentBlock> output;
        private Map<String, Object> metadata;

        /**
         * Sets the tool call ID.
         *
         * @param id The tool call ID
         * @return This builder for chaining
         */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * Sets the tool name.
         *
         * @param name The tool name
         * @return This builder for chaining
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets a single content block as output.
         *
         * @param output Single content block
         * @return This builder for chaining
         */
        public Builder output(ContentBlock output) {
            this.output = List.of(output);
            return this;
        }

        /**
         * Sets a list of content blocks as output.
         *
         * @param output List of content blocks
         * @return This builder for chaining
         */
        public Builder output(List<ContentBlock> output) {
            this.output = output;
            return this;
        }

        /**
         * Sets the metadata for the tool result.
         *
         * @param metadata Metadata map
         * @return This builder for chaining
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Builds a new ToolResultBlock with the configured properties.
         *
         * @return A new ToolResultBlock instance
         */
        public ToolResultBlock build() {
            return new ToolResultBlock(id, name, output, metadata);
        }
    }
}
