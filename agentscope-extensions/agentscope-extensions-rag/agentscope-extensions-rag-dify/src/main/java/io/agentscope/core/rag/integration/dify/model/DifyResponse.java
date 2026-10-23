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
package io.agentscope.core.rag.integration.dify.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response model for Dify knowledge base retrieval API.
 *
 * <p>This class maps the JSON response from Dify's retrieve endpoint:
 * <pre>{@code
 * {
 *   "query": { "content": "..." },
 *   "records": [
 *     {
 *       "segment": {
 *         "id": "...",
 *         "document_id": "...",
 *         "content": "...",
 *         "position": 1,
 *         "document": { "id": "...", "name": "..." }
 *       },
 *       "score": 0.95
 *     }
 *   ]
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DifyResponse {

    private Query query;
    private List<Record> records;

    public Query getQuery() {
        return query;
    }

    public void setQuery(Query query) {
        this.query = query;
    }

    public List<Record> getRecords() {
        return records;
    }

    public void setRecords(List<Record> records) {
        this.records = records;
    }

    /**
     * Query information from the response.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Query {
        private String content;

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }

    /**
     * A single retrieval record containing segment and score.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Record {
        private Segment segment;
        private Double score;

        public Segment getSegment() {
            return segment;
        }

        public void setSegment(Segment segment) {
            this.segment = segment;
        }

        public Double getScore() {
            return score;
        }

        public void setScore(Double score) {
            this.score = score;
        }
    }

    /**
     * Segment information from a retrieved document chunk.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Segment {
        private String id;

        @JsonProperty("document_id")
        private String documentId;

        private String content;
        private String answer;
        private Integer position;

        @JsonProperty("word_count")
        private Integer wordCount;

        private Integer tokens;
        private List<String> keywords;

        @JsonProperty("index_node_id")
        private String indexNodeId;

        @JsonProperty("index_node_hash")
        private String indexNodeHash;

        @JsonProperty("hit_count")
        private Integer hitCount;

        private Boolean enabled;

        @JsonProperty("disabled_at")
        private Long disabledAt;

        @JsonProperty("disabled_by")
        private String disabledBy;

        private String status;

        @JsonProperty("created_by")
        private String createdBy;

        @JsonProperty("created_at")
        private Long createdAt;

        @JsonProperty("indexing_at")
        private Long indexingAt;

        @JsonProperty("completed_at")
        private Long completedAt;

        private String error;

        @JsonProperty("stopped_at")
        private Long stoppedAt;

        private DocumentInfo document;

        // Getters and Setters
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getDocumentId() {
            return documentId;
        }

        public void setDocumentId(String documentId) {
            this.documentId = documentId;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getAnswer() {
            return answer;
        }

        public void setAnswer(String answer) {
            this.answer = answer;
        }

        public Integer getPosition() {
            return position;
        }

        public void setPosition(Integer position) {
            this.position = position;
        }

        public Integer getWordCount() {
            return wordCount;
        }

        public void setWordCount(Integer wordCount) {
            this.wordCount = wordCount;
        }

        public Integer getTokens() {
            return tokens;
        }

        public void setTokens(Integer tokens) {
            this.tokens = tokens;
        }

        public List<String> getKeywords() {
            return keywords;
        }

        public void setKeywords(List<String> keywords) {
            this.keywords = keywords;
        }

        public String getIndexNodeId() {
            return indexNodeId;
        }

        public void setIndexNodeId(String indexNodeId) {
            this.indexNodeId = indexNodeId;
        }

        public String getIndexNodeHash() {
            return indexNodeHash;
        }

        public void setIndexNodeHash(String indexNodeHash) {
            this.indexNodeHash = indexNodeHash;
        }

        public Integer getHitCount() {
            return hitCount;
        }

        public void setHitCount(Integer hitCount) {
            this.hitCount = hitCount;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Long getDisabledAt() {
            return disabledAt;
        }

        public void setDisabledAt(Long disabledAt) {
            this.disabledAt = disabledAt;
        }

        public String getDisabledBy() {
            return disabledBy;
        }

        public void setDisabledBy(String disabledBy) {
            this.disabledBy = disabledBy;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getCreatedBy() {
            return createdBy;
        }

        public void setCreatedBy(String createdBy) {
            this.createdBy = createdBy;
        }

        public Long getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Long createdAt) {
            this.createdAt = createdAt;
        }

        public Long getIndexingAt() {
            return indexingAt;
        }

        public void setIndexingAt(Long indexingAt) {
            this.indexingAt = indexingAt;
        }

        public Long getCompletedAt() {
            return completedAt;
        }

        public void setCompletedAt(Long completedAt) {
            this.completedAt = completedAt;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public Long getStoppedAt() {
            return stoppedAt;
        }

        public void setStoppedAt(Long stoppedAt) {
            this.stoppedAt = stoppedAt;
        }

        public DocumentInfo getDocument() {
            return document;
        }

        public void setDocument(DocumentInfo document) {
            this.document = document;
        }
    }

    /**
     * Document information nested within a segment.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DocumentInfo {
        private String id;

        @JsonProperty("data_source_type")
        private String dataSourceType;

        private String name;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getDataSourceType() {
            return dataSourceType;
        }

        public void setDataSourceType(String dataSourceType) {
            this.dataSourceType = dataSourceType;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
