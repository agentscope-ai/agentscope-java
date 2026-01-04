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
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

/**
 * RAGFlow API response model.
 *
 * <p><b>Note:</b> The {@code data} field may be {@code false}, {@code null}, or an object
 * depending on the API response. A custom deserializer ({@link ResponseDataDeserializer}) handles
 * these cases.
 *
 * @author RAGFlow Integration Team
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RAGFlowResponse {

    @JsonProperty("code")
    private Integer code;

    @JsonProperty("message")
    private String message;

    @JsonProperty("data")
    @JsonDeserialize(using = ResponseDataDeserializer.class)
    private ResponseData data;

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public ResponseData getData() {
        return data;
    }

    public void setData(ResponseData data) {
        this.data = data;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResponseData {

        @JsonProperty("chunks")
        private List<RAGFlowChunk> chunks;

        @JsonProperty("total")
        private Integer total;

        @JsonProperty("doc_aggs")
        private List<DocAgg> docAggs;

        public List<RAGFlowChunk> getChunks() {
            return chunks;
        }

        public void setChunks(List<RAGFlowChunk> chunks) {
            this.chunks = chunks;
        }

        public Integer getTotal() {
            return total;
        }

        public void setTotal(Integer total) {
            this.total = total;
        }

        public List<DocAgg> getDocAggs() {
            return docAggs;
        }

        public void setDocAggs(List<DocAgg> docAggs) {
            this.docAggs = docAggs;
        }
    }

    /**
     * Document aggregation information.
     *
     * <p>This class represents document-level aggregation data returned by RAGFlow API, showing
     * how many chunks were retrieved from each document.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DocAgg {

        @JsonProperty("doc_id")
        private String docId;

        @JsonProperty("doc_name")
        private String docName;

        @JsonProperty("count")
        private Integer count;

        public String getDocId() {
            return docId;
        }

        public void setDocId(String docId) {
            this.docId = docId;
        }

        public String getDocName() {
            return docName;
        }

        public void setDocName(String docName) {
            this.docName = docName;
        }

        public Integer getCount() {
            return count;
        }

        public void setCount(Integer count) {
            this.count = count;
        }

        @Override
        public String toString() {
            return "DocAgg{"
                    + "docId='"
                    + docId
                    + '\''
                    + ", docName='"
                    + docName
                    + '\''
                    + ", count="
                    + count
                    + '}';
        }
    }
}
