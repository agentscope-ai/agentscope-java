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
package io.agentscope.core.rag.integration.haystack.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class HayStackDocument {

    @JsonProperty("id")
    private String id;

    @JsonProperty("content")
    private String content;

    @JsonProperty("blob")
    private ByteStream blob;

    @JsonProperty("meta")
    private Map<String, Object> meta;

    @JsonProperty("score")
    private Double score;

    @JsonProperty("embedding")
    private List<Double> embedding;

    @JsonProperty("sparse_embedding")
    private SparseEmbedding sparseEmbedding;

    // ===== getters & setters =====

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

    public ByteStream getBlob() {
        return blob;
    }

    public void setBlob(ByteStream blob) {
        this.blob = blob;
    }

    public Map<String, Object> getMeta() {
        return meta;
    }

    public void setMeta(Map<String, Object> meta) {
        this.meta = meta;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public List<Double> getEmbedding() {
        return embedding;
    }

    public void setEmbedding(List<Double> embedding) {
        this.embedding = embedding;
    }

    public SparseEmbedding getSparseEmbedding() {
        return sparseEmbedding;
    }

    public void setSparseEmbedding(SparseEmbedding sparseEmbedding) {
        this.sparseEmbedding = sparseEmbedding;
    }

    @Override
    public String toString() {
        return "HayStackDocument{"
                + "id='"
                + id
                + '\''
                + ", content='"
                + content
                + '\''
                + ", blob="
                + blob
                + ", meta="
                + meta
                + ", score="
                + score
                + ", embedding="
                + embedding
                + ", sparseEmbedding="
                + sparseEmbedding
                + '}';
    }
}
