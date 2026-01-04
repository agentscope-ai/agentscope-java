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
package io.agentscope.core.rag.model;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Document metadata containing content and chunking information.
 *
 * <p>This class stores metadata about a document chunk, including the content
 * (which can be text, image, video, etc.), document ID, chunk ID, and optional
 * custom payload fields.
 *
 * <p>The content field uses {@link ContentBlock} which is a sealed hierarchy
 * supporting different content types (TextBlock, ImageBlock, VideoBlock, etc.).
 *
 * <p>The payload field allows storing custom metadata such as file name,
 * department, author, creation time, tags, and other business-specific fields.
 * These fields are stored as key-value pairs and will be persisted to vector
 * databases along with the document content.
 *
 * <p>Example usage with payload:
 * <pre>{@code
 * Map<String, Object> payload = new HashMap<>();
 * payload.put("filename", "report.pdf");
 * payload.put("department", "Finance");
 * payload.put("author", "John Doe");
 * payload.put("created_at", "2024-01-15T10:30:00Z");
 * payload.put("tags", Arrays.asList("urgent", "quarterly"));
 *
 * TextBlock content = TextBlock.builder().text("Document content").build();
 * DocumentMetadata metadata = new DocumentMetadata(content, "doc-123", "chunk-0", payload);
 * }</pre>
 */
public class DocumentMetadata {

    private final ContentBlock content;
    private final String docId;
    private final String chunkId;
    private final Map<String, Object> payload;

    /**
     * Creates a new DocumentMetadata instance without custom payload.
     *
     * <p>This constructor is provided for backward compatibility. For new code,
     * consider using the constructor with payload parameter or the builder pattern
     * if you need to add custom metadata fields.
     *
     * @param content the content block (text, image, video, etc.)
     * @param docId the document ID
     * @param chunkId the chunk ID within the document
     */
    public DocumentMetadata(ContentBlock content, String docId, String chunkId) {
        this(content, docId, chunkId, null);
    }

    /**
     * Creates a new DocumentMetadata instance with custom payload.
     *
     * <p>The payload map is copied to prevent external modifications. The returned
     * payload from {@link #getPayload()} will be an unmodifiable view.
     *
     * @param content the content block (text, image, video, etc.)
     * @param docId the document ID
     * @param chunkId the chunk ID within the document
     * @param payload the custom metadata fields (can be null or empty)
     * @throws IllegalArgumentException if content, docId, or chunkId is null
     */
    public DocumentMetadata(
            ContentBlock content, String docId, String chunkId, Map<String, Object> payload) {
        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }
        if (docId == null) {
            throw new IllegalArgumentException("Document ID cannot be null");
        }
        if (chunkId == null) {
            throw new IllegalArgumentException("Chunk ID cannot be null");
        }
        this.content = content;
        this.docId = docId;
        this.chunkId = chunkId;
        // Create an unmodifiable copy to prevent external modifications
        this.payload =
                payload != null && !payload.isEmpty()
                        ? Collections.unmodifiableMap(new HashMap<>(payload))
                        : Collections.emptyMap();
    }

    /**
     * Gets the content block.
     *
     * @return the content block
     */
    public ContentBlock getContent() {
        return content;
    }

    /**
     * Gets the document ID.
     *
     * @return the document ID
     */
    public String getDocId() {
        return docId;
    }

    /**
     * Gets the chunk ID.
     *
     * @return the chunk ID
     */
    public String getChunkId() {
        return chunkId;
    }

    /**
     * Gets the custom payload metadata.
     *
     * <p>Returns an unmodifiable map of custom metadata fields. This map contains
     * business-specific fields such as filename, department, author, tags, etc.
     * The map is never null but may be empty if no payload was provided.
     *
     * @return an unmodifiable map of custom metadata fields (never null)
     */
    public Map<String, Object> getPayload() {
        return payload;
    }

    /**
     * Gets a specific payload value by key.
     *
     * <p>This is a convenience method to retrieve individual payload values without
     * needing to access the entire payload map.
     *
     * @param key the payload key
     * @return the payload value, or null if the key doesn't exist
     * @throws NullPointerException if key is null
     */
    public Object getPayloadValue(String key) {
        if (key == null) {
            throw new NullPointerException("Payload key cannot be null");
        }
        return payload.get(key);
    }

    /**
     * Checks if the payload contains a specific key.
     *
     * <p>Use this method to safely check for the existence of a payload field
     * before attempting to retrieve its value.
     *
     * @param key the payload key to check
     * @return true if the key exists in the payload, false otherwise
     * @throws NullPointerException if key is null
     */
    public boolean hasPayloadKey(String key) {
        if (key == null) {
            throw new NullPointerException("Payload key cannot be null");
        }
        return payload.containsKey(key);
    }

    /**
     * Gets the text content from the content block.
     *
     * <p>This is a convenience method that extracts text from the ContentBlock.
     * For TextBlock, it returns the text. For other block types, it returns their
     * string representation.
     *
     * @return the text content, or empty string if not available
     */
    public String getContentText() {
        if (content instanceof TextBlock textBlock) {
            return textBlock.getText();
        }
        return content != null ? content.toString() : "";
    }

    /**
     * Creates a new builder for constructing DocumentMetadata instances.
     *
     * <p>The builder pattern provides a fluent API for creating DocumentMetadata
     * with optional payload fields. This is especially useful when you need to
     * add multiple custom metadata fields.
     *
     * <p>Example usage:
     * <pre>{@code
     * DocumentMetadata metadata = DocumentMetadata.builder()
     *     .content(TextBlock.builder().text("Content").build())
     *     .docId("doc-123")
     *     .chunkId("chunk-0")
     *     .addPayload("filename", "report.pdf")
     *     .addPayload("department", "Finance")
     *     .build();
     * }</pre>
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing DocumentMetadata instances with fluent API.
     *
     * <p>This builder provides a convenient way to create DocumentMetadata objects,
     * especially when working with custom payload fields. All fields can be set
     * independently, and the builder validates required fields when {@link #build()}
     * is called.
     */
    public static class Builder {
        private ContentBlock content;
        private String docId;
        private String chunkId;
        private Map<String, Object> payload;

        private Builder() {}

        /**
         * Sets the content block.
         *
         * @param content the content block (text, image, video, etc.)
         * @return this builder for method chaining
         */
        public Builder content(ContentBlock content) {
            this.content = content;
            return this;
        }

        /**
         * Sets the document ID.
         *
         * @param docId the document ID
         * @return this builder for method chaining
         */
        public Builder docId(String docId) {
            this.docId = docId;
            return this;
        }

        /**
         * Sets the chunk ID.
         *
         * @param chunkId the chunk ID within the document
         * @return this builder for method chaining
         */
        public Builder chunkId(String chunkId) {
            this.chunkId = chunkId;
            return this;
        }

        /**
         * Sets the entire payload map.
         *
         * <p>This replaces any previously set payload. The map is copied to prevent
         * external modifications.
         *
         * @param payload the custom metadata fields (can be null)
         * @return this builder for method chaining
         */
        public Builder payload(Map<String, Object> payload) {
            this.payload = payload != null ? new HashMap<>(payload) : null;
            return this;
        }

        /**
         * Adds a single payload field.
         *
         * <p>This method allows adding payload fields one at a time. If the payload
         * map doesn't exist yet, it will be created automatically.
         *
         * @param key the payload key
         * @param value the payload value
         * @return this builder for method chaining
         * @throws NullPointerException if key is null
         */
        public Builder addPayload(String key, Object value) {
            if (key == null) {
                throw new NullPointerException("Payload key cannot be null");
            }
            if (this.payload == null) {
                this.payload = new HashMap<>();
            }
            this.payload.put(key, value);
            return this;
        }

        /**
         * Builds a new DocumentMetadata instance with the configured values.
         *
         * @return a new DocumentMetadata instance
         * @throws IllegalArgumentException if required fields (content, docId, chunkId) are null
         */
        public DocumentMetadata build() {
            return new DocumentMetadata(content, docId, chunkId, payload);
        }
    }
}
