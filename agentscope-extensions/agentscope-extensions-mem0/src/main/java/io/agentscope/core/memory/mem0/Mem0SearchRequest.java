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
import java.util.ArrayList;
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
 * <p>The v2 API requires filters to be structured with logical operators (AND, OR, NOT):
 * <ul>
 *   <li>Entity filters (user_id, agent_id, app_id, run_id) must be OR-connected
 *       because a single memory cannot have multiple entity values</li>
 *   <li>Metadata filters are OR-connected for multiple key-value pairs</li>
 *   <li>Entity filters and metadata filters are AND-connected at the top level</li>
 * </ul>
 *
 * <p>Example of correct filter structure:
 * <pre>{@code
 * {
 *   "filters": {
 *     "AND": [
 *       {
 *         "OR": [
 *           { "user_id": "user123" },
 *           { "agent_id": "agentA" }
 *         ]
 *       },
 *       {
 *         "OR": [
 *           { "metadata": { "category": "travel" } },
 *           { "metadata": { "priority": "high" } }
 *         ]
 *       }
 *     ]
 *   }
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Mem0SearchRequest {

    /** The search query string for semantic similarity matching. */
    private String query;

    /** API version (default: "v2"). */
    private String version = "v2";

    /**
     * Filters to apply to the search using logical operators (AND, OR, NOT).
     * This field is required by the API and must follow the nested structure.
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

    /** Default constructor for Jackson. */
    public Mem0SearchRequest() {
        this.topK = 10;
        this.version = "v2";
        this.filters = new HashMap<>();
    }

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

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String query;
        private String version = "v2";
        private Integer topK = 10;
        private List<String> fields;
        private Boolean rerank;
        private Boolean keywordSearch;
        private Boolean filterMemories;
        private Double threshold;
        private String orgId;
        private String projectId;

        private String userId;
        private String agentId;
        private String runId;
        private String appId;
        private Map<String, Object> metadata = new HashMap<>();

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder appId(String appId) {
            this.appId = appId;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            if (metadata != null) {
                this.metadata.putAll(metadata);
            }
            return this;
        }

        public Builder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

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
            request.setTopK(topK);
            request.setFields(fields);
            request.setRerank(rerank);
            request.setKeywordSearch(keywordSearch);
            request.setFilterMemories(filterMemories);
            request.setThreshold(threshold);
            request.setOrgId(orgId);
            request.setProjectId(projectId);

            request.setFilters(buildNestedFilters());

            return request;
        }

        private Map<String, Object> buildNestedFilters() {
            List<Map<String, Object>> andConditions = new ArrayList<>();

            List<Map<String, Object>> entityFilters = buildEntityFilters();
            if (!entityFilters.isEmpty()) {
                Map<String, Object> entityOr = new HashMap<>();
                entityOr.put("OR", entityFilters);
                andConditions.add(entityOr);
            }

            List<Map<String, Object>> metadataFilters = buildMetadataFilters();
            if (!metadataFilters.isEmpty()) {
                Map<String, Object> metadataOr = new HashMap<>();
                metadataOr.put("OR", metadataFilters);
                andConditions.add(metadataOr);
            }

            if (andConditions.isEmpty()) {
                return new HashMap<>();
            } else if (andConditions.size() == 1) {
                return andConditions.get(0);
            } else {
                Map<String, Object> result = new HashMap<>();
                result.put("AND", andConditions);
                return result;
            }
        }

        private List<Map<String, Object>> buildEntityFilters() {
            List<Map<String, Object>> filters = new ArrayList<>();

            if (userId != null && !userId.isEmpty()) {
                Map<String, Object> filter = new HashMap<>();
                filter.put("user_id", userId);
                filters.add(filter);
            }
            if (agentId != null && !agentId.isEmpty()) {
                Map<String, Object> filter = new HashMap<>();
                filter.put("agent_id", agentId);
                filters.add(filter);
            }
            if (runId != null && !runId.isEmpty()) {
                Map<String, Object> filter = new HashMap<>();
                filter.put("run_id", runId);
                filters.add(filter);
            }
            if (appId != null && !appId.isEmpty()) {
                Map<String, Object> filter = new HashMap<>();
                filter.put("app_id", appId);
                filters.add(filter);
            }

            return filters;
        }

        private List<Map<String, Object>> buildMetadataFilters() {
            List<Map<String, Object>> filters = new ArrayList<>();

            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                Map<String, Object> metadataFilter = new HashMap<>();
                Map<String, Object> metadataValue = new HashMap<>();
                metadataValue.put(entry.getKey(), entry.getValue());
                metadataFilter.put("metadata", metadataValue);
                filters.add(metadataFilter);
            }

            return filters;
        }
    }
}
