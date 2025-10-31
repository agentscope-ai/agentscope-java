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
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.beans.Transient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Represents a message in the AgentScope framework.
 *
 * <p>Messages are the primary communication unit between agents, users, and tools.
 * Each message has a role (user, assistant, system, or tool), content blocks,
 * and optional metadata.
 *
 * <p>Content blocks can include text, images, audio, video, thinking content,
 * tool use blocks, and tool result blocks. The content is stored as an immutable
 * list for thread safety.
 *
 * <p>Messages are serialized to JSON using Jackson and include a unique ID
 * for tracking purposes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Msg {

    private final String id;

    private final String name;

    private final MsgRole role;

    private final List<ContentBlock> content;

    private final Map<String, Object> metadata;

    /**
     * Creates a new message with the specified fields.
     *
     * @param id Unique identifier for the message
     * @param name Optional name for the message (can be null)
     * @param role The role of the message sender (user, assistant, system, or tool)
     * @param content List of content blocks that make up the message content
     * @param metadata Optional metadata map for additional information
     */
    @JsonCreator
    private Msg(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("role") MsgRole role,
            @JsonProperty("content") List<ContentBlock> content,
            @JsonProperty("metadata") Map<String, Object> metadata) {
        this.id = id;
        this.name = name;
        this.role = role;
        this.content =
                content == null
                        ? List.of()
                        : Collections.unmodifiableList(new ArrayList<>(content));
        this.metadata =
                metadata == null ? null : Collections.unmodifiableMap(new HashMap<>(metadata));
    }

    /**
     * Creates a new message builder with a randomly generated ID.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the unique identifier of this message.
     *
     * @return The message ID
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the optional name of this message.
     *
     * @return The message name, or null if not set
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the role of the message sender.
     *
     * @return The message role (user, assistant, system, or tool)
     */
    public MsgRole getRole() {
        return role;
    }

    /**
     * Gets the immutable list of content blocks in this message.
     *
     * @return The content blocks list, may be empty but never null
     */
    public List<ContentBlock> getContent() {
        return content;
    }

    /**
     * Gets the metadata associated with this message.
     *
     * @return The metadata map, or null if not set
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Check if this message has content blocks of the specified type (type-safe).
     * @param blockClass Block class to check for
     * @param <T> Content block type
     * @return true if at least one block of the type exists
     */
    @Transient
    @JsonIgnore
    public <T extends ContentBlock> boolean hasContentBlocks(Class<T> blockClass) {
        return content.stream().anyMatch(blockClass::isInstance);
    }

    /**
     * Get all content blocks of the specified type (type-safe).
     * @param blockClass Block class to filter for
     * @param <T> Content block type
     * @return List of matching blocks
     */
    @SuppressWarnings("unchecked")
    @Transient
    @JsonIgnore
    public <T extends ContentBlock> List<T> getContentBlocks(Class<T> blockClass) {
        return content.stream()
                .filter(blockClass::isInstance)
                .map(b -> (T) b)
                .collect(Collectors.toList());
    }

    /**
     * Get the first content block, or null if empty.
     * @return First content block or null
     */
    @Transient
    @JsonIgnore
    public ContentBlock getFirstContentBlock() {
        return content.isEmpty() ? null : content.get(0);
    }

    /**
     * Get the first content block of the specified type (type-safe).
     * @param blockClass Block class to search for
     * @param <T> Content block type
     * @return First matching block or null
     */
    @SuppressWarnings("unchecked")
    @Transient
    @JsonIgnore
    public <T extends ContentBlock> T getFirstContentBlock(Class<T> blockClass) {
        return content.stream()
                .filter(blockClass::isInstance)
                .map(b -> (T) b)
                .findFirst()
                .orElse(null);
    }

    /**
     * Check if this message contains structured data in metadata.
     *
     * @return true if metadata is present and non-empty
     */
    @Transient
    @JsonIgnore
    public boolean hasStructuredData() {
        return metadata != null && !metadata.isEmpty();
    }

    /**
     * Extract structured data from message metadata and convert it to the specified type.
     *
     * <p>This method is useful when the message contains structured input from a user agent
     * or structured output from an LLM. The metadata map is converted to a Java object
     * using Jackson's ObjectMapper.
     *
     * <p>Example usage:
     * <pre>{@code
     * public class TaskPlan {
     *     public String goal;
     *     public int priority;
     * }
     *
     * Msg msg = userAgent.call(null, TaskPlan.class).block();
     * TaskPlan plan = msg.getStructuredData(TaskPlan.class);
     * }</pre>
     *
     * @param targetClass The class to convert metadata into
     * @param <T> Type of the structured data
     * @return The structured data object
     * @throws IllegalStateException if no metadata exists
     * @throws IllegalArgumentException if conversion fails
     */
    @Transient
    @JsonIgnore
    public <T> T getStructuredData(Class<T> targetClass) {
        if (metadata == null || metadata.isEmpty()) {
            throw new IllegalStateException(
                    "No structured data in message. Use hasStructuredData() to check first.");
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.convertValue(metadata, targetClass);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to convert metadata to "
                            + targetClass.getSimpleName()
                            + ". Ensure the target class has appropriate fields matching metadata"
                            + " keys.",
                    e);
        }
    }

    public static class Builder {

        private String id;

        private String name;

        private MsgRole role;

        private List<ContentBlock> content;

        private Map<String, Object> metadata;

        /**
         * Creates a new builder with a randomly generated message ID.
         */
        public Builder() {
            randomId();
        }

        /**
         * Sets the unique identifier for the message.
         *
         * @param id The message ID
         * @return This builder for chaining
         */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * Generates a random UUID for the message ID.
         */
        private void randomId() {
            this.id = UUID.randomUUID().toString();
        }

        /**
         * Sets the optional name for the message.
         *
         * @param name The message name
         * @return This builder for chaining
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the role for the message.
         *
         * @param role The message role (user, assistant, system, or tool)
         * @return This builder for chaining
         */
        public Builder role(MsgRole role) {
            this.role = role;
            return this;
        }

        /**
         * Set content from a list of content blocks.
         * @param content List of content blocks
         * @return This builder
         */
        public Builder content(List<ContentBlock> content) {
            this.content = content;
            return this;
        }

        /**
         * Set content from a single content block (convenience method).
         * The block will be wrapped in a list automatically.
         * @param block Single content block
         * @return This builder
         */
        public Builder content(ContentBlock block) {
            this.content = block == null ? List.of() : List.of(block);
            return this;
        }

        /**
         * Set content from varargs content blocks (convenience method).
         * @param blocks Content blocks
         * @return This builder
         */
        public Builder content(ContentBlock... blocks) {
            this.content = blocks == null ? List.of() : List.of(blocks);
            return this;
        }

        /**
         * Set metadata for structured output.
         * @param metadata Metadata map
         * @return This builder
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Builds a new message instance with the configured properties.
         *
         * @return A new immutable message
         */
        public Msg build() {
            return new Msg(id, name, role, content, metadata);
        }
    }
}
