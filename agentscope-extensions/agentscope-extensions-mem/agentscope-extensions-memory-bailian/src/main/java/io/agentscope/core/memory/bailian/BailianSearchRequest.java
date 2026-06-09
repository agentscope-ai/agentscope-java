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

/**
 * Request object for searching memories in Bailian Memory API.
 *
 * <p>This request is sent to the POST /api/v2/apps/memory/memory_nodes/search endpoint
 * to retrieve relevant memories based on semantic similarity to the query messages.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BailianSearchRequest {

    /** User identifier for filtering memories (required). */
    @JsonProperty("user_id")
    private String userId;

    /** List of query messages for semantic similarity search. */
    private List<BailianMessage> messages;

    /** Memory library identifier for filtering. */
    @JsonProperty("memory_library_id")
    private String memoryLibraryId;

    /**
     * List project identifiers for memory rules.
     * Multi-memory fragment rule identifiers can be passed in for mixed retrieval.
     */
    @JsonProperty("project_ids")
    private List<String> projectIds;

    /** Maximum number of results to return (default: 10). */
    @JsonProperty("top_k")
    private Integer topK;

    /** Minimum similarity score threshold [0, 1], (default: 0.3). */
    @JsonProperty("min_score")
    private Double minScore;

    /** Whether to turn on reordering of search results (default: false). */
    @JsonProperty("enable_rerank")
    private Boolean enableRerank;

    /** Whether to turn on the intent discrimination callback (default: false). */
    @JsonProperty("enable_judge")
    private Boolean enableJudge;

    /** Whether to turn on query rewrite (default: false). */
    @JsonProperty("enable_rewrite")
    private Boolean enableRewrite;

    /** Default constructor for Jackson. */
    public BailianSearchRequest() {}

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
     * Gets the project IDs.
     *
     * @return the project IDs
     */
    public List<String> getProjectIds() {
        return projectIds;
    }

    /**
     * Sets the project IDs.
     *
     * @param projectIds the project IDs
     */
    public void setProjectIds(List<String> projectIds) {
        this.projectIds = projectIds;
    }

    /**
     * Gets the top K.
     *
     * @return the top K value
     */
    public Integer getTopK() {
        return topK;
    }

    /**
     * Sets the top K.
     *
     * @param topK the top K value
     */
    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    /**
     * Gets the minimum score.
     *
     * @return the minimum score threshold
     */
    public Double getMinScore() {
        return minScore;
    }

    /**
     * Sets the minimum score.
     *
     * @param minScore the minimum score threshold
     */
    public void setMinScore(Double minScore) {
        this.minScore = minScore;
    }

    /**
     * Gets the enable rerank value.
     *
     * @return the enable rerank value
     */
    public Boolean getEnableRerank() {
        return enableRerank;
    }

    /**
     * Sets the enable rerank value.
     *
     * @param enableRerank the enable rerank value
     */
    public void setEnableRerank(Boolean enableRerank) {
        this.enableRerank = enableRerank;
    }

    /**
     * Gets the enable judge value.
     *
     * @return the enable judge value
     */
    public Boolean getEnableJudge() {
        return enableJudge;
    }

    /**
     * Sets the enable judge value.
     *
     * @param enableJudge the enable judge value
     */
    public void setEnableJudge(Boolean enableJudge) {
        this.enableJudge = enableJudge;
    }

    /**
     * Gets the enable rewrite value.
     *
     * @return the enable rewrite value
     */
    public Boolean getEnableRewrite() {
        return enableRewrite;
    }

    /**
     * Sets the enable rewrite value.
     *
     * @param enableRewrite the enable rewrite value
     */
    public void setEnableRewrite(Boolean enableRewrite) {
        this.enableRewrite = enableRewrite;
    }

    /**
     * Creates a new builder for BailianSearchRequest.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for BailianSearchRequest.
     */
    public static class Builder {
        private String userId;
        private List<BailianMessage> messages;
        private String memoryLibraryId;
        private List<String> projectIds;
        private Integer topK;
        private Double minScore;
        private Boolean enableRerank;
        private Boolean enableJudge;
        private Boolean enableRewrite;

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
         * Sets the project IDs.
         *
         * @param projectIds the project IDs
         * @return this builder
         */
        public Builder projectIds(List<String> projectIds) {
            this.projectIds = projectIds;
            return this;
        }

        /**
         * Sets the top K.
         *
         * @param topK the top K value
         * @return this builder
         */
        public Builder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

        /**
         * Sets the minimum score.
         *
         * @param minScore the minimum score threshold
         * @return this builder
         */
        public Builder minScore(Double minScore) {
            this.minScore = minScore;
            return this;
        }

        /**
         * Sets the enableRerank value.
         *
         * @param enableRerank the enableRerank value
         * @return this builder
         */
        public Builder enableRerank(Boolean enableRerank) {
            this.enableRerank = enableRerank;
            return this;
        }

        /**
         * Sets the enableJudge value.
         *
         * @param enableJudge the enableJudge value
         * @return this builder
         */
        public Builder enableJudge(Boolean enableJudge) {
            this.enableJudge = enableJudge;
            return this;
        }

        /**
         * Sets the enableRewrite value.
         *
         * @param enableRewrite the enableRewrite value
         * @return this builder
         */
        public Builder enableRewrite(Boolean enableRewrite) {
            this.enableRewrite = enableRewrite;
            return this;
        }

        /**
         * Builds the BailianSearchRequest instance.
         *
         * @return a new BailianSearchRequest instance
         */
        public BailianSearchRequest build() {
            BailianSearchRequest request = new BailianSearchRequest();
            request.setUserId(userId);
            request.setMessages(messages);
            request.setMemoryLibraryId(memoryLibraryId);
            request.setProjectIds(projectIds);
            request.setTopK(topK);
            request.setMinScore(minScore);
            request.setEnableRerank(enableRerank);
            request.setEnableJudge(enableJudge);
            request.setEnableRewrite(enableRewrite);
            return request;
        }
    }
}
