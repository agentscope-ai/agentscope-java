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
import io.agentscope.core.tool.ToolSuspendException;
import java.beans.Transient;
import java.util.HashMap;
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

    /**
     * Metadata key indicating this result is suspended for external execution.
     *
     * <p>Legacy wire encoding from before {@link ToolResultState#SUSPENDED} existed. {@link
     * #isSuspended()} still recognises it for cross-version compatibility. New code should rely
     * on {@link ToolResultState#SUSPENDED} or {@link #isSuspended()} instead of this key.
     */
    @Deprecated public static final String METADATA_SUSPENDED = "agentscope_suspended";

    /**
     * Metadata key carrying the suspend reason (the {@code ToolSuspendException} message) when
     * the tool supplied one. Control-plane information; never rendered through {@code output}.
     */
    public static final String METADATA_SUSPEND_REASON = "agentscope_suspend_reason";

    /**
     * Metadata key marking this result as produced by a provider server tool (Boolean value).
     * Such results (e.g. Anthropic's web_search_tool_result) are returned by the provider inside
     * the assistant message rather than produced by local tool execution.
     */
    public static final String METADATA_SERVER_TOOL = ToolUseBlock.METADATA_SERVER_TOOL;

    private final String id;
    private final String name;
    private final List<ContentBlock> output;
    private final Map<String, Object> metadata;
    private final ToolResultState state;
    private final ToolExecutionDetails executionDetails;

    @JsonCreator
    public ToolResultBlock(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("output") List<ContentBlock> output,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("state") ToolResultState state,
            @JsonProperty("executionDetails") ToolExecutionDetails executionDetails) {
        this.id = id;
        this.name = name;
        this.output = output != null ? List.copyOf(output) : List.of();
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        this.state = state != null ? state : ToolResultState.RUNNING;
        this.executionDetails = executionDetails;
    }

    public ToolResultBlock(
            String id,
            String name,
            List<ContentBlock> output,
            Map<String, Object> metadata,
            ToolResultState state) {
        this(id, name, output, metadata, state, null);
    }

    public ToolExecutionDetails getExecutionDetails() {
        return executionDetails;
    }

    public ToolResultBlock withExecutionDetails(ToolExecutionDetails details) {
        return new ToolResultBlock(id, name, output, metadata, state, details);
    }

    public ToolResultBlock(
            String id, String name, List<ContentBlock> output, Map<String, Object> metadata) {
        this(id, name, output, metadata, null);
    }

    /**
     * Creates a tool result block with a single content block output.
     *
     * @param id Tool call ID
     * @param name Tool name
     * @param output Single content block as output
     */
    public ToolResultBlock(String id, String name, ContentBlock output) {
        this(id, name, List.of(output), null, null);
    }

    /**
     * Creates a tool result block with a list of content blocks as output.
     *
     * @param id Tool call ID
     * @param name Tool name
     * @param output List of content blocks as output
     */
    public ToolResultBlock(String id, String name, List<ContentBlock> output) {
        this(id, name, output, null, null);
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
     * Gets the tool result state.
     *
     * @return The tool result state
     */
    public ToolResultState getState() {
        return state;
    }

    /**
     * Returns a copy of this block with the given state.
     *
     * @param state The new state
     * @return A new ToolResultBlock with the updated state
     */
    public ToolResultBlock withState(ToolResultState state) {
        return new ToolResultBlock(
                this.id, this.name, this.output, this.metadata, state, executionDetails);
    }

    /**
     * Checks if this result is suspended for external execution.
     *
     * <p>Recognises the canonical encoding ({@link ToolResultState#SUSPENDED}) and the legacy
     * wire marker ({@link #METADATA_SUSPENDED}), so blocks serialized by older
     * versions keep working.
     *
     * @return true if this result is suspended, false otherwise
     */
    @Transient
    @JsonInclude
    public boolean isSuspended() {
        return state == ToolResultState.SUSPENDED
                || Boolean.TRUE.equals(metadata.get(METADATA_SUSPENDED));
    }

    /**
     * Checks whether this result was produced by a provider server tool.
     *
     * @return true if this result comes from a server-side tool execution
     */
    @JsonIgnore
    public boolean isServerTool() {
        return Boolean.TRUE.equals(metadata.get(METADATA_SERVER_TOOL));
    }

    /**
     * Creates a suspended tool result from a ToolSuspendException.
     *
     * <p>The result carries no output: the tool has not executed, and the real result arrives
     * from the caller on resume. The state is {@link ToolResultState#SUSPENDED} and, when the
     * exception supplied a reason, it travels in {@link #METADATA_SUSPEND_REASON} metadata.
     *
     * @param toolUse The tool use block that triggered the exception
     * @param exception The exception thrown by the tool
     * @return A suspended ToolResultBlock
     */
    public static ToolResultBlock suspended(ToolUseBlock toolUse, ToolSuspendException exception) {
        Map<String, Object> metadata = new HashMap<>();
        // Kept for one transition window: older readers still detect suspension via this
        // legacy marker, while current code reads the SUSPENDED state above.
        metadata.put(METADATA_SUSPENDED, true);
        if (exception.getReason() != null) {
            metadata.put(METADATA_SUSPEND_REASON, exception.getReason());
        }
        return new ToolResultBlock(
                toolUse.getId(),
                toolUse.getName(),
                List.of(),
                Map.copyOf(metadata),
                ToolResultState.SUSPENDED);
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
     * Gets the suspend reason when this result is suspended and the tool supplied one.
     *
     * @return the reason, or null when absent or when this result is not suspended
     */
    @Transient
    @JsonIgnore
    public String getSuspendReason() {
        if (!isSuspended()) {
            return null;
        }
        Object reason = metadata.get(METADATA_SUSPEND_REASON);
        return reason instanceof String s ? s : null;
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
     * Create a successful result with text output (for tool method return values).
     *
     * <p>Unlike {@link #text(String)}, this sets {@link ToolResultState#SUCCESS} explicitly. An
     * unset state is coerced to {@link ToolResultState#RUNNING} by the constructor, which the agent
     * runtime treats as "state unknown" and falls back to inspecting the output text for an error
     * prefix. Tools whose successful output is external content — file contents, HTTP bodies, shell
     * output — should therefore prefer this factory, so that content which merely looks like an
     * error message is not misclassified as a failed call.
     *
     * @param text Text content
     * @return ToolResultBlock with text output and state SUCCESS
     */
    public static ToolResultBlock success(String text) {
        return new ToolResultBlock(
                null,
                null,
                List.of(TextBlock.builder().text(text).build()),
                null,
                ToolResultState.SUCCESS);
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
                null,
                ToolResultState.ERROR);
    }

    /**
     * Create an error result for a tool call.
     *
     * <p>The {@code [ERROR]} prefix is used by the agent runtime to preserve the error marker in
     * generated tool results.
     *
     * @param toolId Tool call ID
     * @param errorMessage Error message
     * @return Error ToolResultBlock for use in a message
     */
    public static ToolResultBlock error(String toolId, String errorMessage) {
        return new ToolResultBlock(
                toolId,
                null,
                List.of(TextBlock.builder().text("[ERROR] " + errorMessage).build()),
                null,
                ToolResultState.ERROR);
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
        return new ToolResultBlock(
                id, name, this.output, this.metadata, this.state, executionDetails);
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
        private ToolResultState state;
        private ToolExecutionDetails executionDetails;

        public Builder executionDetails(ToolExecutionDetails details) {
            this.executionDetails = details;
            return this;
        }

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
         * Sets the tool result state.
         *
         * @param state The tool result state
         * @return This builder for chaining
         */
        public Builder state(ToolResultState state) {
            this.state = state;
            return this;
        }

        /**
         * Builds a new ToolResultBlock with the configured properties.
         *
         * @return A new ToolResultBlock instance
         */
        public ToolResultBlock build() {
            return new ToolResultBlock(id, name, output, metadata, state, executionDetails);
        }
    }
}
