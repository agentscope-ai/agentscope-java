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
package io.agentscope.core.rag.integration.ragflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * RAGFlow chunk (document segment) model.
 *
 * <p>This class represents a chunk (text segment) returned by RAGFlow's retrieval API.
 *
 * <p><b>Real API Response Format:</b>
 *
 * <pre>{@code
 * {
 *   "id": "d78435d142bd5cf6704da62c778795c5",
 *   "content": "ragflow content",
 *   "content_ltks": "ragflow content",
 *   "document_id": "5c5999ec7be811ef9cab0242ac120005",
 *   "document_keyword": "1.txt",
 *   "highlight": "<em>ragflow</em> content",
 *   "kb_id": "c7ee74067a2c11efb21c0242ac120006",
 *   "similarity": 0.9669436601210759,
 *   "vector_similarity": 0.8898122004035864,
 *   "term_similarity": 1.0,
 *   "positions": [[0, 100], [200, 300]],
 *   "image_id": "",
 *   "important_keywords": ["ragflow", "content"]
 * }
 * }</pre>
 *
 * <p><b>Note:</b> {@code @JsonIgnoreProperties(ignoreUnknown = true)} ensures forward
 * compatibility with API changes.
 *
 * @author RAGFlow Integration Team
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RAGFlowChunk {

    // Core fields
    @JsonProperty("id")
    private String id;

    @JsonProperty("content")
    private String content;

    @JsonProperty("content_ltks")
    private String contentLtks;

    @JsonProperty("highlight")
    private String highlight;

    // Document information
    @JsonProperty("document_id")
    private String documentId;

    @JsonProperty("document_keyword")
    private String documentKeyword;

    @JsonProperty("kb_id")
    private String kbId;

    // Similarity scores
    @JsonProperty("similarity")
    private Double similarity;

    @JsonProperty("vector_similarity")
    private Double vectorSimilarity;

    @JsonProperty("term_similarity")
    private Double termSimilarity;

    // Additional fields
    @JsonProperty("positions")
    private List<List<Integer>> positions;

    @JsonProperty("image_id")
    private String imageId;

    @JsonProperty("important_keywords")
    private List<String> importantKeywords;

    // Custom metadata (if any)
    @JsonProperty("metadata")
    private Map<String, Object> metadata;

    // Legacy field for compatibility
    @JsonProperty("score")
    private Double score;

    @JsonProperty("dataset_id")
    private String datasetId;

    @JsonProperty("document_name")
    private String documentName;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getContentLtks() {
        return contentLtks;
    }

    public void setContentLtks(String contentLtks) {
        this.contentLtks = contentLtks;
    }

    public String getHighlight() {
        return highlight;
    }

    public void setHighlight(String highlight) {
        this.highlight = highlight;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getDocumentKeyword() {
        return documentKeyword;
    }

    public void setDocumentKeyword(String documentKeyword) {
        this.documentKeyword = documentKeyword;
    }

    public String getKbId() {
        return kbId;
    }

    public void setKbId(String kbId) {
        this.kbId = kbId;
    }

    public Double getSimilarity() {
        return similarity;
    }

    public void setSimilarity(Double similarity) {
        this.similarity = similarity;
    }

    public Double getVectorSimilarity() {
        return vectorSimilarity;
    }

    public void setVectorSimilarity(Double vectorSimilarity) {
        this.vectorSimilarity = vectorSimilarity;
    }

    public Double getTermSimilarity() {
        return termSimilarity;
    }

    public void setTermSimilarity(Double termSimilarity) {
        this.termSimilarity = termSimilarity;
    }

    public List<List<Integer>> getPositions() {
        return positions;
    }

    public void setPositions(List<List<Integer>> positions) {
        this.positions = positions;
    }

    public String getImageId() {
        return imageId;
    }

    public void setImageId(String imageId) {
        this.imageId = imageId;
    }

    public List<String> getImportantKeywords() {
        return importantKeywords;
    }

    public void setImportantKeywords(List<String> importantKeywords) {
        this.importantKeywords = importantKeywords;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    // Legacy compatibility fields
    public String getDatasetId() {
        // Use kb_id if available, otherwise fall back to datasetId
        return kbId != null ? kbId : datasetId;
    }

    public void setDatasetId(String datasetId) {
        this.datasetId = datasetId;
    }

    public String getDocumentName() {
        // Use document_keyword if available, otherwise fall back to documentName
        return documentKeyword != null ? documentKeyword : documentName;
    }

    public void setDocumentName(String documentName) {
        this.documentName = documentName;
    }

    /**
     * Gets the similarity score.
     *
     * <p>RAGFlow API returns {@code similarity} as the primary score field. This method provides
     * compatibility with both field names.
     *
     * @return the similarity score (0.0-1.0), or null if not available
     */
    public Double getScore() {
        // RAGFlow uses 'similarity' as the primary score
        return similarity != null ? similarity : score;
    }

    public void setScore(Double score) {
        this.score = score;
    }
}
