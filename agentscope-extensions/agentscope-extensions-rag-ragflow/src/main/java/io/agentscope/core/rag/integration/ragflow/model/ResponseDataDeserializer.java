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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom deserializer for RAGFlow ResponseData.
 *
 * <p>RAGFlow API may return "data": false or "data": null in some cases (e.g., errors, no
 * results). This deserializer handles those cases gracefully by returning null instead of throwing
 * an exception.
 *
 * <p><b>API Response Examples:</b>
 *
 * <pre>{@code
 * // Success case
 * {"code": 0, "data": {"chunks": [...], "total": 1}}
 *
 * // Error or empty case
 * {"code": 404, "message": "Dataset not found", "data": false}
 * {"code": 0, "message": "No results", "data": null}
 * }</pre>
 *
 * @author RAGFlow Integration Team
 */
public class ResponseDataDeserializer extends JsonDeserializer<RAGFlowResponse.ResponseData> {

    @Override
    public RAGFlowResponse.ResponseData deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {

        JsonNode node = p.getCodec().readTree(p);

        // Handle cases where data is false, null, or not an object
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }

        // Deserialize the object normally
        RAGFlowResponse.ResponseData responseData = new RAGFlowResponse.ResponseData();

        // Parse chunks
        if (node.has("chunks") && node.get("chunks").isArray()) {
            List<RAGFlowChunk> chunks = new ArrayList<>();
            for (JsonNode chunkNode : node.get("chunks")) {
                RAGFlowChunk chunk = p.getCodec().treeToValue(chunkNode, RAGFlowChunk.class);
                if (chunk != null) {
                    chunks.add(chunk);
                }
            }
            responseData.setChunks(chunks);
        }

        // Parse total
        if (node.has("total") && !node.get("total").isNull()) {
            responseData.setTotal(node.get("total").asInt());
        }

        // Parse doc_aggs
        if (node.has("doc_aggs") && node.get("doc_aggs").isArray()) {
            List<RAGFlowResponse.DocAgg> docAggs = new ArrayList<>();
            for (JsonNode aggNode : node.get("doc_aggs")) {
                RAGFlowResponse.DocAgg docAgg =
                        p.getCodec().treeToValue(aggNode, RAGFlowResponse.DocAgg.class);
                if (docAgg != null) {
                    docAggs.add(docAgg);
                }
            }
            responseData.setDocAggs(docAggs);
        }

        return responseData;
    }
}
