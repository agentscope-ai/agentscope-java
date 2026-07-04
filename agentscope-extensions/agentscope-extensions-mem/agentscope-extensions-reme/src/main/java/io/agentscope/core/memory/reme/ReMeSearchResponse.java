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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Response object from ReMe's search memory API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReMeSearchResponse {

    /** The answer text containing retrieved memories. */
    private String answer;

    /** Whether the operation was successful. */
    private Boolean success;

    /** Metadata containing additional information. */
    private Metadata metadata;

    /** Default constructor for Jackson. */
    public ReMeSearchResponse() {}

    // Getters and Setters

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public Metadata getMetadata() {
        return metadata;
    }

    public void setMetadata(Metadata metadata) {
        this.metadata = metadata;
    }

    /**
     * Extracts search result text fragments.
     */
    public List<String> getMemories() {
        if (metadata != null && metadata.getResults() != null) {
            return metadata.getResults().stream()
                    .map(SearchResult::getText)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    @Override
    public String toString() {
        return "ReMeSearchResponse{"
                + "answer='"
                + answer
                + '\''
                + ", success="
                + success
                + ", metadata="
                + metadata
                + '}';
    }

    /** Metadata returned by ReMe's search job. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Metadata {
        private List<SearchResult> results;

        private Map<String, Object> counts;

        @JsonProperty("link_expansion")
        private Map<String, Object> linkExpansion;

        public List<SearchResult> getResults() {
            return results;
        }

        public void setResults(List<SearchResult> results) {
            this.results = results;
        }

        public Map<String, Object> getCounts() {
            return counts;
        }

        public void setCounts(Map<String, Object> counts) {
            this.counts = counts;
        }

        public Map<String, Object> getLinkExpansion() {
            return linkExpansion;
        }

        public void setLinkExpansion(Map<String, Object> linkExpansion) {
            this.linkExpansion = linkExpansion;
        }

        @Override
        public String toString() {
            return "Metadata{"
                    + "results="
                    + (results != null ? results.size() + " items" : "null")
                    + '}';
        }
    }

    /** Represents a single search hit in the response. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SearchResult {
        private String id;

        private String text;

        private Map<String, Object> metadata;

        private String path;

        @JsonProperty("start_line")
        private Integer startLine;

        @JsonProperty("end_line")
        private Integer endLine;

        private Map<String, Object> scores;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public Integer getStartLine() {
            return startLine;
        }

        public void setStartLine(Integer startLine) {
            this.startLine = startLine;
        }

        public Integer getEndLine() {
            return endLine;
        }

        public void setEndLine(Integer endLine) {
            this.endLine = endLine;
        }

        public Map<String, Object> getScores() {
            return scores;
        }

        public void setScores(Map<String, Object> scores) {
            this.scores = scores;
        }
    }
}
