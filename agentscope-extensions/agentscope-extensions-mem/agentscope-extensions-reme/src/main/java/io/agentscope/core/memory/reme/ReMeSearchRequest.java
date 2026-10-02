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
package io.agentscope.core.memory.reme;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request object for searching memories in ReMe API.
 *
 * <p>This request is sent to the ReMe API's {@code POST /retrieve_personal_memory} endpoint
 * to retrieve relevant memories based on a query string.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReMeSearchRequest {

    /** Workspace identifier for memory organization. */
    @JsonProperty("workspace_id")
    private String workspaceId;

    /** The search query string. */
    private String query;

    /** Maximum number of results to return. */
    @JsonProperty("top_k")
    private Integer topK;

    /** Default constructor for Jackson. */
    public ReMeSearchRequest() {
        this.topK = 5; // Default value
    }

    /**
     * Creates a new ReMeSearchRequest with specified workspace ID, query, and topK.
     *
     * @param workspaceId The workspace identifier
     * @param query The search query
     * @param topK Maximum number of results
     */
    public ReMeSearchRequest(String workspaceId, String query, Integer topK) {
        this.workspaceId = workspaceId;
        this.query = query;
        this.topK = topK;
    }

    // Getters and Setters

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    /**
     * Creates a new builder for ReMeSearchRequest.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for ReMeSearchRequest. */
    public static class Builder {
        private String workspaceId;
        private String query;
        private Integer topK = 5;

        public Builder workspaceId(String workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

        public ReMeSearchRequest build() {
            return new ReMeSearchRequest(workspaceId, query, topK);
        }
    }
}
