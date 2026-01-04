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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Request object for adding memories to Mem0 API.
 *
 * <p>This request is sent to the Mem0 API's {@code POST /v1/memories/} endpoint to
 * record new memories. Mem0 will process the messages and extract memorable information
 * using LLM-powered inference (unless {@code infer} is set to false).
 *
 * <p>The metadata fields (agentId, userId, runId, appId) are used to organize and filter
 * memories, enabling multi-tenant and multi-agent scenarios.
 *
 * <p>The API supports v2 version which is recommended for new applications. Set {@code version}
 * to "v2" to use the latest features.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Mem0AddRequest {

    /** List of messages to process for memory extraction. */
    private List<Mem0Message> messages;

    /** Agent identifier for memory organization. */
    @JsonProperty("agent_id")
    private String agentId;

    /** User identifier for memory organization. */
    @JsonProperty("user_id")
    private String userId;

    /** Application identifier for memory organization. */
    @JsonProperty("app_id")
    private String appId;

    /** Run/session identifier for memory organization. */
    @JsonProperty("run_id")
    private String runId;

    /** Additional metadata for storing context about the memory. */
    private Map<String, Object> metadata;

    /** String to include specific preferences in the memory. */
    private String includes;

    /** String to exclude specific preferences in the memory. */
    private String excludes;

    /** Whether to use LLM inference to extract memories (default: true). */
    private Boolean infer;

    /**
     * Output format version (v1.0 or v1.1, default: v1.1).
     * v1.0 (deprecated) returns array directly, v1.1 returns object with 'results' key.
     */
    @JsonProperty("output_format")
    private String outputFormat;

    /** Custom categories with category name and description. */
    @JsonProperty("custom_categories")
    private Map<String, Object> customCategories;

    /** Custom instructions for handling and organizing memories. */
    @JsonProperty("custom_instructions")
    private String customInstructions;

    /** Whether the memory is immutable (default: false). */
    private Boolean immutable;

    /** Whether to add the memory completely asynchronously (default: true). */
    @JsonProperty("async_mode")
    private Boolean asyncMode;

    /** Unix timestamp of the memory. */
    private Long timestamp;

    /** Expiration date (format: YYYY-MM-DD). */
    @JsonProperty("expiration_date")
    private String expirationDate;

    /** Organization ID. */
    @JsonProperty("org_id")
    private String orgId;

    /** Project ID. */
    @JsonProperty("project_id")
    private String projectId;

    /** API version (recommended: "v2" for new applications). */
    private String version;

    /**
     * Type of memory (e.g., "semantic", "procedural").
     *
     * @deprecated Not in official API spec, kept for backward compatibility
     */
    @Deprecated
    @JsonProperty("memory_type")
    private String memoryType;

    /** Default constructor for Jackson. */
    public Mem0AddRequest() {
        this.infer = true;
        this.outputFormat = "v1.1";
        this.asyncMode = true;
        this.immutable = false;
    }

    // Getters and Setters

    public List<Mem0Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Mem0Message> messages) {
        this.messages = messages;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public String getIncludes() {
        return includes;
    }

    public void setIncludes(String includes) {
        this.includes = includes;
    }

    public String getExcludes() {
        return excludes;
    }

    public void setExcludes(String excludes) {
        this.excludes = excludes;
    }

    public Boolean getInfer() {
        return infer;
    }

    public void setInfer(Boolean infer) {
        this.infer = infer;
    }

    public String getOutputFormat() {
        return outputFormat;
    }

    public void setOutputFormat(String outputFormat) {
        this.outputFormat = outputFormat;
    }

    public Map<String, Object> getCustomCategories() {
        return customCategories;
    }

    public void setCustomCategories(Map<String, Object> customCategories) {
        this.customCategories = customCategories;
    }

    public String getCustomInstructions() {
        return customInstructions;
    }

    public void setCustomInstructions(String customInstructions) {
        this.customInstructions = customInstructions;
    }

    public Boolean getImmutable() {
        return immutable;
    }

    public void setImmutable(Boolean immutable) {
        this.immutable = immutable;
    }

    public Boolean getAsyncMode() {
        return asyncMode;
    }

    public void setAsyncMode(Boolean asyncMode) {
        this.asyncMode = asyncMode;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public String getExpirationDate() {
        return expirationDate;
    }

    public void setExpirationDate(String expirationDate) {
        this.expirationDate = expirationDate;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    /**
     * Gets the memory type.
     *
     * @return The memory type
     * @deprecated Not in official API spec
     */
    @Deprecated
    public String getMemoryType() {
        return memoryType;
    }

    /**
     * Sets the memory type.
     *
     * @param memoryType The memory type
     * @deprecated Not in official API spec
     */
    @Deprecated
    public void setMemoryType(String memoryType) {
        this.memoryType = memoryType;
    }

    /**
     * Creates a new builder for Mem0AddRequest.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for Mem0AddRequest. */
    public static class Builder {
        private List<Mem0Message> messages;
        private String agentId;
        private String userId;
        private String appId;
        private String runId;
        private Map<String, Object> metadata;
        private String includes;
        private String excludes;
        private Boolean infer = true;
        private String outputFormat = "v1.1";
        private Map<String, Object> customCategories;
        private String customInstructions;
        private Boolean immutable = false;
        private Boolean asyncMode = true;
        private Long timestamp;
        private String expirationDate;
        private String orgId;
        private String projectId;
        private String version;
        private String memoryType;

        public Builder messages(List<Mem0Message> messages) {
            this.messages = messages;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder appId(String appId) {
            this.appId = appId;
            return this;
        }

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder includes(String includes) {
            this.includes = includes;
            return this;
        }

        public Builder excludes(String excludes) {
            this.excludes = excludes;
            return this;
        }

        public Builder infer(Boolean infer) {
            this.infer = infer;
            return this;
        }

        public Builder outputFormat(String outputFormat) {
            this.outputFormat = outputFormat;
            return this;
        }

        public Builder customCategories(Map<String, Object> customCategories) {
            this.customCategories = customCategories;
            return this;
        }

        public Builder customInstructions(String customInstructions) {
            this.customInstructions = customInstructions;
            return this;
        }

        public Builder immutable(Boolean immutable) {
            this.immutable = immutable;
            return this;
        }

        public Builder asyncMode(Boolean asyncMode) {
            this.asyncMode = asyncMode;
            return this;
        }

        public Builder timestamp(Long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder expirationDate(String expirationDate) {
            this.expirationDate = expirationDate;
            return this;
        }

        public Builder orgId(String orgId) {
            this.orgId = orgId;
            return this;
        }

        public Builder projectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        /**
         * Sets the memory type.
         *
         * @param memoryType The memory type
         * @return This builder
         * @deprecated Not in official API spec
         */
        @Deprecated
        public Builder memoryType(String memoryType) {
            this.memoryType = memoryType;
            return this;
        }

        public Mem0AddRequest build() {
            Mem0AddRequest request = new Mem0AddRequest();
            request.setMessages(messages);
            request.setAgentId(agentId);
            request.setUserId(userId);
            request.setAppId(appId);
            request.setRunId(runId);
            request.setMetadata(metadata);
            request.setIncludes(includes);
            request.setExcludes(excludes);
            request.setInfer(infer);
            request.setOutputFormat(outputFormat);
            request.setCustomCategories(customCategories);
            request.setCustomInstructions(customInstructions);
            request.setImmutable(immutable);
            request.setAsyncMode(asyncMode);
            request.setTimestamp(timestamp);
            request.setExpirationDate(expirationDate);
            request.setOrgId(orgId);
            request.setProjectId(projectId);
            request.setVersion(version);
            request.setMemoryType(memoryType);
            return request;
        }
    }
}
