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

/**
 * HayStack RAG API response model.
 *
 * <p>Since Haystack does not provide a standard REST API format,
 * the API response of your <b>custom-built Haystack RAG server</b> should conform to the following format.
 *
 * <p><b> For Normal </b>
 * <pre>{@code
 *{
 *   "code": 0,
 *   "documents": [
 *     {
 *       "id": "0fab3c1c6c433368",
 *       "content": "Test Content",
 *       "blob": null,
 *       "meta": {
 *         "file_path": "test.txt"
 *       },
 *       "score": 1.2303546667099,
 *       "embedding": [
 *         0.01008153147995472,
 *         -0.04555170238018036,
 *         -0.024434546008706093
 *       ],
 *       "sparse_embedding": null,
 *       "error": null
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p> You can build your Haystack RAG server like this:
 *
 * <pre>{@code
 * @app.post("/retrieve")
 * async def retriever(req: Request):
 *     result = retriever.run(
 *         query=req.query,
 *         top_k=req.top_k,
 *     )
 *     documents = result["documents"]
 *
 *     return {
 *         "code": 0,
 *         "documents": documents,
 *         "error": None
 *     }
 * }</pre>
 *
 * <p><b> For SentenceWindowRetriever </b>
 * <pre>{@code
 * {
 *   "code": 0,
 *   "context_windows": [
 *     "Test Content 0 "
 *   ],
 *   "context_documents": [
 *     {
 *       "id": "9e4cd66e14377767fdc6b9",
 *       "content": "Test Content 1",
 *       "meta": {
 *         "source": "usr_90.txt",
 *         "source_id": "1528904be8e57",
 *         "page_number": 1,
 *         "split_id": 21,
 *         "split_idx_start": 11199,
 *         "_split_overlap": [
 *           {
 *             "doc_id": "55ee242643b2439c8",
 *             "range": [433, 570]
 *           },
 *           {
 *             "doc_id": "cdc785a04643cbd",
 *             "range": [0, 146]
 *           }
 *         ]
 *       }
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p> You can build your Haystack RAG server like this:
 *
 * <pre>{@code
 * @app.post("/retrieve")
 * async def run_retriever(req: RetrieverRequest):
 *     retrieved = bm25.run(
 *         query=req.query,
 *         top_k=req.top_k
 *     )
 *     retrieved_documents = retrieved["documents"]
 *
 *     sentence_window = SentenceWindowRetriever(document_store=doc_store)
 *
 *     result = sentence_window.run(
 *         retrieved_documents=retrieved_documents,
 *         window_size=req.window_size
 *     )
 *
 *     return {
 *         "code": 0,
 *         "context_windows": result["context_windows"],
 *         "context_documents": result["context_documents"],
 *         "error": None
 *     }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HayStackResponse {

    @JsonProperty("code")
    private Integer code;

    @JsonProperty("documents")
    private List<HayStackDocument> documents;

    @JsonProperty("context_windows")
    private List<String> contextWindows;

    @JsonProperty("context_documents")
    private List<HayStackDocument> contextDocuments;

    @JsonProperty("error")
    private String error;

    // ===== getters & setters =====

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public List<HayStackDocument> getDocuments() {
        return documents;
    }

    public void setDocuments(List<HayStackDocument> documents) {
        this.documents = documents;
    }

    public List<String> getContextWindows() {
        return contextWindows;
    }

    public void setContextWindows(List<String> contextWindows) {
        this.contextWindows = contextWindows;
    }

    public List<HayStackDocument> getContextDocuments() {
        return contextDocuments;
    }

    public void setContextDocuments(List<HayStackDocument> contextDocuments) {
        this.contextDocuments = contextDocuments;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    @Override
    public String toString() {
        return "HayStackResponse{"
                + "code="
                + code
                + ", documents="
                + documents
                + ", contextWindows="
                + contextWindows
                + ", contextDocuments="
                + contextDocuments
                + ", error='"
                + error
                + '\''
                + '}';
    }
}
