/*
 * Copyright 2024-2025 the original author or authors.
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
public class ToolResultBlock extends ContentBlock {

    private final String id;
    private final String name;
    private final ContentBlock output;
    private final Map<String, Object> metadata;

    public ToolResultBlock(
            String id, String name, ContentBlock output, Map<String, Object> metadata) {
        this.id = id;
        this.name = name;
        this.output = output;
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public ToolResultBlock(String id, String name, ContentBlock output) {
        this(id, name, output, null);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public ContentBlock getOutput() {
        return output;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    @Override
    public ContentBlockType getType() {
        return ContentBlockType.TOOL_USE;
    }

    /**
     * Create a simple text result (for tool method return values).
     *
     * @param text Text content
     * @return ToolResultBlock with text output
     */
    public static ToolResultBlock text(String text) {
        return new ToolResultBlock(null, null, TextBlock.builder().text(text).build(), null);
    }

    /**
     * Create an error result (for tool method return values).
     *
     * @param errorMessage Error message
     * @return ToolResultBlock with error output
     */
    public static ToolResultBlock error(String errorMessage) {
        return new ToolResultBlock(
                null, null, TextBlock.builder().text("Error: " + errorMessage).build(), null);
    }

    /**
     * Create a result with output only (for tool method return values).
     *
     * @param output Content block output
     * @return ToolResultBlock with the given output
     */
    public static ToolResultBlock of(ContentBlock output) {
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
        return new ToolResultBlock(id, name, output, metadata);
    }

    /**
     * Create a result from multiple content blocks by aggregating them.
     *
     * @param contentBlocks List of content blocks to aggregate
     * @return ToolResultBlock with aggregated output
     */
    public static ToolResultBlock fromContentBlocks(List<ContentBlock> contentBlocks) {
        ContentBlock aggregated = aggregateContent(contentBlocks);
        return new ToolResultBlock(null, null, aggregated, null);
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
     * Aggregate multiple content blocks into a single content block.
     *
     * @param contentBlocks List of content blocks
     * @return Single aggregated ContentBlock
     */
    private static ContentBlock aggregateContent(List<ContentBlock> contentBlocks) {
        if (contentBlocks == null || contentBlocks.isEmpty()) {
            return TextBlock.builder().text("").build();
        }

        if (contentBlocks.size() == 1) {
            return contentBlocks.get(0);
        }

        // Multiple blocks - merge into text
        StringBuilder combined = new StringBuilder();
        for (ContentBlock block : contentBlocks) {
            if (block instanceof TextBlock tb) {
                if (!combined.isEmpty()) {
                    combined.append("\n");
                }
                combined.append(tb.getText());
            } else {
                if (!combined.isEmpty()) {
                    combined.append("\n");
                }
                combined.append("[").append(block.getClass().getSimpleName()).append("]");
            }
        }

        return TextBlock.builder().text(combined.toString()).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String name;
        private ContentBlock output;
        private Map<String, Object> metadata;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder output(ContentBlock output) {
            this.output = output;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public ToolResultBlock build() {
            return new ToolResultBlock(id, name, output, metadata);
        }
    }
}
