/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;

/**
 * Custom Jackson deserializer for {@link AguiMessage} that handles the union type
 * {@code content: string | InputContent[]}.
 *
 * <p>The AG-UI protocol allows the {@code content} field to be either a plain text string
 * or an array of multimodal content parts. This deserializer inspects the JSON token type
 * and routes accordingly.
 */
public class AguiMessageDeserializer extends JsonDeserializer<AguiMessage> {

    @Override
    public AguiMessage deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        JsonNode node = mapper.readTree(p);

        String id = getTextOrNull(node, "id");
        String role = getTextOrNull(node, "role");
        String toolCallId = getTextOrNull(node, "toolCallId");

        List<AguiToolCall> toolCalls = null;
        if (node.has("toolCalls") && !node.get("toolCalls").isNull()) {
            toolCalls =
                    mapper.convertValue(
                            node.get("toolCalls"), new TypeReference<List<AguiToolCall>>() {});
        }

        // Handle union type: content can be string or array
        String content = null;
        List<AguiContentPart> contentParts = null;

        JsonNode contentNode = node.get("content");
        if (contentNode != null && !contentNode.isNull()) {
            if (contentNode.isTextual()) {
                content = contentNode.asText();
            } else if (contentNode.isArray()) {
                contentParts =
                        mapper.convertValue(
                                contentNode, new TypeReference<List<AguiContentPart>>() {});
            }
        }

        return new AguiMessage(id, role, content, contentParts, toolCalls, toolCallId);
    }

    private String getTextOrNull(JsonNode node, String field) {
        JsonNode fieldNode = node.get(field);
        if (fieldNode != null && !fieldNode.isNull()) {
            return fieldNode.asText();
        }
        return null;
    }
}
