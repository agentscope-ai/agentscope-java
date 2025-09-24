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
package io.agentscope.core.tool;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response from a tool execution, following the Python AgentScope ToolResponse pattern.
 *
 * This class represents the result of executing a tool function, including:
 * - Content blocks (text, images, etc.)
 * - Metadata about the execution
 * - Streaming and interruption flags
 * - Unique identifier for tracking
 */
public class ToolResponse {

    private final List<ContentBlock> content;
    private final Map<String, Object> metadata;
    private final boolean isStream;
    private final boolean isLast;
    private final boolean isInterrupted;
    private final String id;

    /**
     * Create a tool response with all parameters.
     *
     * @param content List of content blocks representing the tool result
     * @param metadata Optional metadata about the execution
     * @param isStream Whether this response is part of a streaming result
     * @param isLast Whether this is the last response in a stream
     * @param isInterrupted Whether the execution was interrupted
     * @param id Unique identifier for this response
     */
    public ToolResponse(
            List<ContentBlock> content,
            Map<String, Object> metadata,
            boolean isStream,
            boolean isLast,
            boolean isInterrupted,
            String id) {
        this.content = content != null ? List.copyOf(content) : List.of();
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        this.isStream = isStream;
        this.isLast = isLast;
        this.isInterrupted = isInterrupted;
        this.id = id != null ? id : generateId();
    }

    /**
     * Create a simple tool response with content only.
     *
     * @param content List of content blocks
     */
    public ToolResponse(List<ContentBlock> content) {
        this(content, null, false, true, false, null);
    }

    /**
     * Create an error response.
     *
     * @param errorMessage Error message to include
     * @return ToolResponse containing the error
     */
    public static ToolResponse error(String errorMessage) {
        return new ToolResponse(
                List.of(TextBlock.builder().text("Error: " + errorMessage).build()),
                null,
                false,
                true,
                false,
                null);
    }

    /**
     * Create an interrupted response.
     *
     * @return ToolResponse indicating interruption
     */
    public static ToolResponse interrupted() {
        return new ToolResponse(
                List.of(
                        TextBlock.builder()
                                .text(
                                        "<system-info>The tool call has been interrupted by the"
                                                + " user.</system-info>")
                                .build()),
                null,
                true,
                true,
                true,
                null);
    }

    /**
     * Get the content blocks from this response.
     *
     * @return Immutable list of content blocks
     */
    public List<ContentBlock> getContent() {
        return content;
    }

    /**
     * Get metadata about this response.
     *
     * @return Immutable map of metadata
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Check if this response is part of a stream.
     *
     * @return True if this is a streaming response
     */
    public boolean isStream() {
        return isStream;
    }

    /**
     * Check if this is the last response in a stream.
     *
     * @return True if this is the final response
     */
    public boolean isLast() {
        return isLast;
    }

    /**
     * Check if the execution was interrupted.
     *
     * @return True if the tool execution was interrupted
     */
    public boolean isInterrupted() {
        return isInterrupted;
    }

    /**
     * Get the unique identifier for this response.
     *
     * @return Response ID
     */
    public String getId() {
        return id;
    }

    /**
     * Generate a unique identifier based on timestamp.
     *
     * @return Unique ID string
     */
    private static String generateId() {
        return Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public String toString() {
        return String.format(
                "ToolResponse{id=%s, content=%d blocks, stream=%s, last=%s, interrupted=%s}",
                id, content.size(), isStream, isLast, isInterrupted);
    }
}
