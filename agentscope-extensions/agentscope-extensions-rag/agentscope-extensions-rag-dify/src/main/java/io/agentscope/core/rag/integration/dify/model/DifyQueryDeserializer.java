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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/**
 * Custom deserializer for {@link DifyResponse.Query}.
 *
 * <p>Dify serializes the top-level {@code query} field of a retrieval response in two different
 * ways depending on the deployment:
 *
 * <pre>{@code
 * // Dify Cloud (api.dify.ai)
 * {"query": "ping", "records": [...]}
 *
 * // Self-hosted Dify
 * {"query": {"content": "ping"}, "records": [...]}
 * }</pre>
 *
 * <p>The field is informational only, so an unexpected shape must not fail the whole response.
 * Modelling it as an object alone discarded every retrieved record when talking to Dify Cloud.
 */
public class DifyQueryDeserializer extends JsonDeserializer<DifyResponse.Query> {

    @Override
    public DifyResponse.Query deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {

        JsonNode node = p.getCodec().readTree(p);

        if (node == null || node.isNull()) {
            return null;
        }

        DifyResponse.Query query = new DifyResponse.Query();

        if (node.isTextual()) {
            query.setContent(node.asText());
            return query;
        }

        if (node.isObject()) {
            JsonNode content = node.get("content");
            if (content != null && content.isTextual()) {
                query.setContent(content.asText());
            }
            return query;
        }

        // Unknown shape: the query text is informational, keep the records.
        return null;
    }
}
