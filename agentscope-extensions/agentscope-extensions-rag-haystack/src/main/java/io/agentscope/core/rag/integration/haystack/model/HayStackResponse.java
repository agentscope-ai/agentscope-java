/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
 * the API response of your <b>custom-built Haystack RAG server</b> should conform to the following format:
 *
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
 *
 * <p> In typical scenarios, you can build your Haystack RAG server like this:
 *
 * <pre>{@code
 * @app.post("/retrieve", response_model=HayStackResponse)
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
