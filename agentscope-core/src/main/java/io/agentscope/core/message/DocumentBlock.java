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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Document content for model input (PDF, Word, plain text, and other provider-supported formats).
 *
 * <p>Unlike {@link DataBlock}, which is a generic binary/media container, this block carries
 * document semantics that converters need in order to route the content: optional MIME type and
 * size fields, a human-readable file name, and a source that may be a provider-side file ID
 * rather than bytes.
 *
 * <p>Documents are user input. {@link MsgRole#USER} accepts this block; providers that cannot
 * consume documents report it instead of silently dropping the content.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DocumentBlock extends ContentBlock {

    private final Source source;

    private final String id;

    private final String name;

    @JsonProperty("mime_type")
    private final String mimeType;

    @JsonProperty("size_bytes")
    private final Long sizeBytes;

    private final Map<String, Object> metadata;

    /**
     * Creates a new document block for JSON deserialization.
     *
     * @param source The document source (file ID, URL, or Base64); required
     * @param id Stable identifier; if null, a fresh UUID hex is generated
     * @param name Optional human-readable name (e.g. file name); may be null
     * @param mimeType Optional MIME type of the document (e.g. "application/pdf"); may be null
     * @param sizeBytes Optional size of the document in bytes; may be null
     * @param metadata Optional provider-specific metadata; may be null
     * @throws NullPointerException if source is null
     */
    @JsonCreator
    private DocumentBlock(
            @JsonProperty("source") Source source,
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("mime_type") String mimeType,
            @JsonProperty("size_bytes") Long sizeBytes,
            @JsonProperty("metadata") Map<String, Object> metadata) {
        this.source = Objects.requireNonNull(source, "source cannot be null");
        this.id =
                (id != null && !id.isEmpty()) ? id : UUID.randomUUID().toString().replace("-", "");
        this.name = name;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.metadata =
                metadata == null ? null : Collections.unmodifiableMap(new HashMap<>(metadata));
    }

    /**
     * Gets the source of this document block.
     *
     * @return The document source (file ID, URL, or Base64)
     */
    public Source getSource() {
        return source;
    }

    /**
     * Gets the identifier of this document block.
     *
     * @return The block id; never null
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the optional name of this document block.
     *
     * @return The block name, or null if not set
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the optional MIME type of the document.
     *
     * @return The MIME type (e.g. "application/pdf"), or null if not set
     */
    public String getMimeType() {
        return mimeType;
    }

    /**
     * Gets the optional size of the document in bytes.
     *
     * @return The size in bytes, or null if not set
     */
    public Long getSizeBytes() {
        return sizeBytes;
    }

    /**
     * Gets the optional provider-specific metadata attached to this block.
     *
     * @return The metadata map, or null if not set
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Creates a new builder for constructing DocumentBlock instances.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DocumentBlock other)) {
            return false;
        }
        return Objects.equals(source, other.source)
                && Objects.equals(id, other.id)
                && Objects.equals(name, other.name)
                && Objects.equals(mimeType, other.mimeType)
                && Objects.equals(sizeBytes, other.sizeBytes)
                && ValueEquality.equal(metadata, other.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, id, name, mimeType, sizeBytes, ValueEquality.hash(metadata));
    }

    /**
     * Builder for constructing DocumentBlock instances.
     */
    public static class Builder {

        private Source source;

        private String id;

        private String name;

        private String mimeType;

        private Long sizeBytes;

        private Map<String, Object> metadata;

        /**
         * Sets the source for the document block.
         *
         * @param source The document source (file ID, URL, or Base64)
         * @return This builder for chaining
         */
        public Builder source(Source source) {
            this.source = source;
            return this;
        }

        /**
         * Sets the identifier for the document block. If left unset, a fresh UUID
         * is generated at {@link #build()} time.
         *
         * @param id The stable id
         * @return This builder for chaining
         */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * Sets the optional name for the document block.
         *
         * @param name The block name (e.g. file name)
         * @return This builder for chaining
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the optional MIME type for the document block.
         *
         * @param mimeType The MIME type (e.g. "application/pdf")
         * @return This builder for chaining
         */
        public Builder mimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        /**
         * Sets the optional size of the document in bytes.
         *
         * @param sizeBytes The size in bytes
         * @return This builder for chaining
         */
        public Builder sizeBytes(Long sizeBytes) {
            this.sizeBytes = sizeBytes;
            return this;
        }

        /**
         * Sets the optional provider-specific metadata for the document block.
         *
         * @param metadata The metadata map
         * @return This builder for chaining
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Builds a new DocumentBlock with the configured fields.
         *
         * @return A new DocumentBlock instance
         * @throws NullPointerException if source is null
         */
        public DocumentBlock build() {
            return new DocumentBlock(source, id, name, mimeType, sizeBytes, metadata);
        }
    }
}
