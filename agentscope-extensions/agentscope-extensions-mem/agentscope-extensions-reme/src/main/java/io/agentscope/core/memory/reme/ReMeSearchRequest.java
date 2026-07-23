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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Request object for searching memories in ReMe API.
 *
 * <p>This request is sent to the ReMe API's {@code POST /search} endpoint.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReMeSearchRequest {

    /** The search query string. */
    private String query;

    /** Maximum number of results to return. */
    private Integer limit;

    /** Minimum search score accepted by ReMe. */
    @JsonProperty("min_score")
    private Double minScore;

    /** Optional job metadata. */
    private Map<String, Object> metadata;

    /**
     * Legacy field kept only for source compatibility. It is not serialized because
     * ReMe 0.4.x search is no longer scoped by workspace ID.
     */
    @JsonIgnore private String workspaceId;

    /** Default constructor for Jackson. */
    public ReMeSearchRequest() {
        this.limit = 5;
        this.minScore = 0.0;
    }

    /**
     * Creates a new ReMeSearchRequest.
     *
     * @param query The search query
     * @param limit Maximum number of results
     * @param minScore Minimum accepted score
     * @param metadata Optional metadata
     */
    public ReMeSearchRequest(
            String query, Integer limit, Double minScore, Map<String, Object> metadata) {
        this.query = query;
        this.limit = limit;
        this.minScore = minScore;
        this.metadata = metadata;
    }

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

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public Double getMinScore() {
        return minScore;
    }

    public void setMinScore(Double minScore) {
        this.minScore = minScore;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    /**
     * Legacy accessor kept for source compatibility.
     *
     * @deprecated Use {@link #getLimit()} instead.
     */
    @Deprecated
    public Integer getTopK() {
        return limit;
    }

    /**
     * Legacy mutator kept for source compatibility.
     *
     * @deprecated Use {@link #setLimit(Integer)} instead.
     */
    @Deprecated
    public void setTopK(Integer topK) {
        this.limit = topK;
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
        private Integer limit = 5;
        private Double minScore = 0.0;
        private Map<String, Object> metadata;

        public Builder workspaceId(String workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder limit(Integer limit) {
            this.limit = limit;
            return this;
        }

        public Builder minScore(Double minScore) {
            this.minScore = minScore;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Legacy builder alias kept for source compatibility.
         *
         * @deprecated Use {@link #limit(Integer)} instead.
         */
        @Deprecated
        public Builder topK(Integer topK) {
            this.limit = topK;
            return this;
        }

        public ReMeSearchRequest build() {
            ReMeSearchRequest request = new ReMeSearchRequest(query, limit, minScore, metadata);
            request.setWorkspaceId(workspaceId);
            return request;
        }
    }
}
