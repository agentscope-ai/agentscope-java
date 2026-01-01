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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Request object for searching memories in Mem0 v2 API.
 *
 * <p>This request is sent to the Mem0 API's {@code POST /v2/memories/search/} endpoint
 * to retrieve relevant memories based on a query string. The search uses semantic
 * similarity to find the most relevant memories.
 *
 * <p>The v2 API uses a filters object to specify search criteria (user_id, agent_id,
 * run_id, app_id, etc.), enabling proper memory isolation in multi-tenant scenarios.
 * The filters field is required by the API and will be sent as an empty object if no
 * filters are specified.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Mem0SearchRequest {

    /** The search query string for semantic similarity matching. */
    private String query;

    /** API version (default: "v2"). */
    private String version = "v2";

    /**
     * Filters to apply to the search (user_id, agent_id, run_id, app_id, etc.).
     * This field is required by the API. An empty map will be sent if no filters are specified.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Map<String, Object> filters = new HashMap<>();

    /** Maximum number of results to return (default: 10). */
    @JsonProperty("top_k")
    private Integer topK;

    /** List of field names to include in the response (optional). */
    private List<String> fields;

    /** Whether to rerank the memories (default: false). */
    private Boolean rerank;

    /** Whether to use keyword search (default: false). */
    @JsonProperty("keyword_search")
    private Boolean keywordSearch;

    /** Whether to filter memories (default: false). */
    @JsonProperty("filter_memories")
    private Boolean filterMemories;

    /** Minimum similarity threshold for returned results (default: 0.3). */
    private Double threshold;

    /** Organization ID (optional). */
    @JsonProperty("org_id")
    private String orgId;

    /** Project ID (optional). */
    @JsonProperty("project_id")
    private String projectId;

    /** User identifier for filtering memories (optional). */
    @JsonProperty("user_id")
    private String userId;

    /** Default constructor for Jackson. */
    public Mem0SearchRequest() {
        this.topK = 10; // Default value per v2 API spec
        this.version = "v2";
        this.filters = new HashMap<>(); // Ensure filters is never null
    }

    // Getters and Setters

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Map<String, Object> getFilters() {
        return filters;
    }

    public void setFilters(Map<String, Object> filters) {
        this.filters = filters != null ? filters : new HashMap<>();
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public List<String> getFields() {
        return fields;
    }

    public void setFields(List<String> fields) {
        this.fields = fields;
    }

    public Boolean getRerank() {
        return rerank;
    }

    public void setRerank(Boolean rerank) {
        this.rerank = rerank;
    }

    public Boolean getKeywordSearch() {
        return keywordSearch;
    }

    public void setKeywordSearch(Boolean keywordSearch) {
        this.keywordSearch = keywordSearch;
    }

    public Boolean getFilterMemories() {
        return filterMemories;
    }

    public void setFilterMemories(Boolean filterMemories) {
        this.filterMemories = filterMemories;
    }

    public Double getThreshold() {
        return threshold;
    }

    public void setThreshold(Double threshold) {
        this.threshold = threshold;
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

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
        // Automatically sync to filters if set
        if (userId != null && filters != null) {
            filters.put("user_id", userId);
        }
    }

    /**
     * Creates a new builder for Mem0SearchRequest.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for Mem0SearchRequest. */
    public static class Builder {
        private String query;
        private String version = "v2";
        private Map<String, Object> filters = new HashMap<>();
        private Integer topK = 10;
        private List<String> fields;
        private Boolean rerank;
        private Boolean keywordSearch;
        private Boolean filterMemories;
        private Double threshold;
        private String orgId;
        private String projectId;
        private String userId;

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder filters(Map<String, Object> filters) {
            this.filters = filters;
            return this;
        }

        /**
         * Convenience method to add agent_id to filters.
         *
         * @param agentId The agent identifier
         * @return This builder
         */
        public Builder agentId(String agentId) {
            if (agentId != null) {
                this.filters.put("agent_id", agentId);
            }
            return this;
        }

        /**
         * Sets the user identifier.
         *
         * <p>This method sets the userId field and also adds it to filters for v2 API compatibility.
         *
         * @param userId The user identifier
         * @return This builder
         */
        public Builder userId(String userId) {
            this.userId = userId;
            if (userId != null) {
                this.filters.put("user_id", userId);
            }
            return this;
        }

        /**
         * Convenience method to add run_id to filters.
         *
         * @param runId The run/session identifier
         * @return This builder
         */
        public Builder runId(String runId) {
            if (runId != null) {
                this.filters.put("run_id", runId);
            }
            return this;
        }

        /**
         * Convenience method to add app_id to filters.
         *
         * @param appId The application identifier
         * @return This builder
         */
        public Builder appId(String appId) {
            if (appId != null) {
                this.filters.put("app_id", appId);
            }
            return this;
        }

        public Builder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

        /**
         * Convenience method for backward compatibility.
         *
         * @param limit Maximum number of results (maps to top_k)
         * @return This builder
         * @deprecated Use {@link #topK(Integer)} instead
         */
        @Deprecated
        public Builder limit(Integer limit) {
            this.topK = limit;
            return this;
        }

        public Builder fields(List<String> fields) {
            this.fields = fields;
            return this;
        }

        public Builder rerank(Boolean rerank) {
            this.rerank = rerank;
            return this;
        }

        public Builder keywordSearch(Boolean keywordSearch) {
            this.keywordSearch = keywordSearch;
            return this;
        }

        public Builder filterMemories(Boolean filterMemories) {
            this.filterMemories = filterMemories;
            return this;
        }

        public Builder threshold(Double threshold) {
            this.threshold = threshold;
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

        public Mem0SearchRequest build() {
            Mem0SearchRequest request = new Mem0SearchRequest();
            request.setQuery(query);
            request.setVersion(version);
            request.setFilters(filters);
            request.setTopK(topK);
            request.setFields(fields);
            request.setRerank(rerank);
            request.setKeywordSearch(keywordSearch);
            request.setFilterMemories(filterMemories);
            request.setThreshold(threshold);
            request.setOrgId(orgId);
            request.setProjectId(projectId);
            // Set userId after filters to ensure it's synced
            if (userId != null) {
                request.setUserId(userId);
            }
            return request;
        }
    }
}
