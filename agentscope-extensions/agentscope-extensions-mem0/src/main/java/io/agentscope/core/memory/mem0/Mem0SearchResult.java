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
package io.agentscope.core.memory.mem0;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Represents a single memory search result from Mem0 v2 API.
 *
 * <p>Each search result contains:
 * <ul>
 *   <li>The memory text content
 *   <li>Unique identifier for the memory
 *   <li>User identifier
 *   <li>Metadata associated with the memory
 *   <li>Categories for classification
 *   <li>Immutability flag
 *   <li>Optional expiration date
 *   <li>Creation and update timestamps
 * </ul>
 *
 * <p>Results are typically ordered by relevance.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Mem0SearchResult {

    /** Unique identifier for this memory (UUID format). */
    private String id;

    /** The actual memory text content. */
    private String memory;

    /** The identifier of the user associated with this memory. */
    @JsonProperty("user_id")
    private String userId;

    /** Additional metadata associated with the memory. */
    private Map<String, Object> metadata;

    /** Categories associated with the memory. */
    private List<String> categories;

    /** Whether the memory is immutable. */
    private Boolean immutable;

    /** The date and time when the memory will expire (optional). */
    @JsonProperty("expiration_date")
    private OffsetDateTime expirationDate;

    /** The timestamp when the memory was created. */
    @JsonProperty("created_at")
    private OffsetDateTime createdAt;

    /** The timestamp when the memory was last updated. */
    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt;

    /**
     * Relevance score returned by v2 search API.
     */
    private Double score;

    /**
     * Structured temporal attributes extracted from the memory.
     * Contains fields like day, month, year, hour, minute, day_of_week, etc.
     */
    @JsonProperty("structured_attributes")
    private Map<String, Object> structuredAttributes;

    /** Default constructor for Jackson. */
    public Mem0SearchResult() {}

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMemory() {
        return memory;
    }

    public void setMemory(String memory) {
        this.memory = memory;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public List<String> getCategories() {
        return categories;
    }

    public void setCategories(List<String> categories) {
        this.categories = categories;
    }

    public Boolean getImmutable() {
        return immutable;
    }

    public void setImmutable(Boolean immutable) {
        this.immutable = immutable;
    }

    public OffsetDateTime getExpirationDate() {
        return expirationDate;
    }

    public void setExpirationDate(OffsetDateTime expirationDate) {
        this.expirationDate = expirationDate;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * Gets the relevance score.
     *
     * @return The relevance score
     */
    public Double getScore() {
        return score;
    }

    /**
     * Sets the relevance score.
     *
     * @param score The relevance score
     */
    public void setScore(Double score) {
        this.score = score;
    }

    /**
     * Gets the structured temporal attributes.
     *
     * @return The structured attributes map
     */
    public Map<String, Object> getStructuredAttributes() {
        return structuredAttributes;
    }

    /**
     * Sets the structured temporal attributes.
     *
     * @param structuredAttributes The structured attributes map
     */
    public void setStructuredAttributes(Map<String, Object> structuredAttributes) {
        this.structuredAttributes = structuredAttributes;
    }

    @Override
    public String toString() {
        return "Mem0SearchResult{"
                + "id='"
                + id
                + '\''
                + ", memory='"
                + memory
                + '\''
                + ", userId='"
                + userId
                + '\''
                + ", categories="
                + categories
                + ", immutable="
                + immutable
                + ", createdAt="
                + createdAt
                + ", updatedAt="
                + updatedAt
                + '}';
    }
}
