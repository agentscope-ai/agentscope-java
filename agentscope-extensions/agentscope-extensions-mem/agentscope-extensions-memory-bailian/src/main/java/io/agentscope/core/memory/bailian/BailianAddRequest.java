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
package io.agentscope.core.memory.bailian;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Request object for adding memories to Bailian Memory API.
 *
 * <p>This request is sent to the POST /api/v2/apps/memory/add endpoint to
 * record new memories. Bailian will process the messages and extract memorable
 * information using LLM-powered inference.
 *
 * <p>The metadata fields are used to organize and filter memories, enabling
 * multi-tenant and multi-user scenarios.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BailianAddRequest {

    /** User identifier for memory organization (required). */
    @JsonProperty("user_id")
    private String userId;

    /** List of conversation messages to process for memory extraction. */
    private List<BailianMessage> messages;

    /** Custom content to directly store as memory (alternative to messages). */
    @JsonProperty("custom_content")
    private String customContent;

    /** Memory library identifier for memory organization. */
    @JsonProperty("memory_library_id")
    private String memoryLibraryId;

    /** Project identifier for memory rules. */
    @JsonProperty("project_id")
    private String projectId;

    /** Profile schema identifier for user profile extraction. */
    @JsonProperty("profile_schema")
    private String profileSchema;

    /** Additional metadata for storing context about the memory. */
    @JsonProperty("meta_data")
    private Map<String, Object> metadata;

    /** Default constructor for Jackson. */
    public BailianAddRequest() {}

    /**
     * Gets the user ID.
     *
     * @return the user ID
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Sets the user ID.
     *
     * @param userId the user ID
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Gets the messages.
     *
     * @return the messages list
     */
    public List<BailianMessage> getMessages() {
        return messages;
    }

    /**
     * Sets the messages.
     *
     * @param messages the messages list
     */
    public void setMessages(List<BailianMessage> messages) {
        this.messages = messages;
    }

    /**
     * Gets the custom content.
     *
     * @return the custom content
     */
    public String getCustomContent() {
        return customContent;
    }

    /**
     * Sets the custom content.
     *
     * @param customContent the custom content
     */
    public void setCustomContent(String customContent) {
        this.customContent = customContent;
    }

    /**
     * Gets the memory library ID.
     *
     * @return the memory library ID
     */
    public String getMemoryLibraryId() {
        return memoryLibraryId;
    }

    /**
     * Sets the memory library ID.
     *
     * @param memoryLibraryId the memory library ID
     */
    public void setMemoryLibraryId(String memoryLibraryId) {
        this.memoryLibraryId = memoryLibraryId;
    }

    /**
     * Gets the project ID.
     *
     * @return the project ID
     */
    public String getProjectId() {
        return projectId;
    }

    /**
     * Sets the project ID.
     *
     * @param projectId the project ID
     */
    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    /**
     * Gets the profile schema.
     *
     * @return the profile schema
     */
    public String getProfileSchema() {
        return profileSchema;
    }

    /**
     * Sets the profile schema.
     *
     * @param profileSchema the profile schema
     */
    public void setProfileSchema(String profileSchema) {
        this.profileSchema = profileSchema;
    }

    /**
     * Gets the metadata.
     *
     * @return the metadata map
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Sets the metadata.
     *
     * @param metadata the metadata map
     */
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    /**
     * Creates a new builder for BailianAddRequest.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for BailianAddRequest.
     */
    public static class Builder {
        private String userId;
        private List<BailianMessage> messages;
        private String customContent;
        private String memoryLibraryId;
        private String projectId;
        private String profileSchema;
        private Map<String, Object> metadata;

        /**
         * Sets the user ID.
         *
         * @param userId the user ID
         * @return this builder
         */
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        /**
         * Sets the messages.
         *
         * @param messages the messages list
         * @return this builder
         */
        public Builder messages(List<BailianMessage> messages) {
            this.messages = messages;
            return this;
        }

        /**
         * Sets the custom content.
         *
         * @param customContent the custom content
         * @return this builder
         */
        public Builder customContent(String customContent) {
            this.customContent = customContent;
            return this;
        }

        /**
         * Sets the memory library ID.
         *
         * @param memoryLibraryId the memory library ID
         * @return this builder
         */
        public Builder memoryLibraryId(String memoryLibraryId) {
            this.memoryLibraryId = memoryLibraryId;
            return this;
        }

        /**
         * Sets the project ID.
         *
         * @param projectId the project ID
         * @return this builder
         */
        public Builder projectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        /**
         * Sets the profile schema.
         *
         * @param profileSchema the profile schema
         * @return this builder
         */
        public Builder profileSchema(String profileSchema) {
            this.profileSchema = profileSchema;
            return this;
        }

        /**
         * Sets the metadata.
         *
         * @param metadata the metadata map
         * @return this builder
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Builds the BailianAddRequest instance.
         *
         * @return a new BailianAddRequest instance
         */
        public BailianAddRequest build() {
            BailianAddRequest request = new BailianAddRequest();
            request.setUserId(userId);
            request.setMessages(messages);
            request.setCustomContent(customContent);
            request.setMemoryLibraryId(memoryLibraryId);
            request.setProjectId(projectId);
            request.setProfileSchema(profileSchema);
            request.setMetadata(metadata);
            return request;
        }
    }
}
