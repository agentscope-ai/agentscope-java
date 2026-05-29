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
package io.agentscope.core.llm.interfacesweb.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small JSON helpers shared by protocol adapters. */
public final class ProtocolJsonUtils {

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ProtocolJsonUtils() {}

    public static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    public static Map<String, Object> parseRequiredObject(String json, String fieldName) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new ProtocolException(
                    "invalid_request_error", fieldName + " must be a valid JSON object");
        }
    }

    public static Map<String, Object> toMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return OBJECT_MAPPER.convertValue(value, new TypeReference<Map<String, Object>>() {});
    }

    public static String toJson(Object value) {
        if (value == null) {
            return "{}";
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    public static JsonNode toJsonNode(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        return OBJECT_MAPPER.valueToTree(value);
    }

    public static String textValue(JsonNode node, String fieldName) {
        JsonNode child = node != null ? node.get(fieldName) : null;
        return child != null && !child.isNull() ? child.asText() : null;
    }

    public static boolean truthy(JsonNode node, String fieldName) {
        JsonNode child = node != null ? node.get(fieldName) : null;
        return child != null && child.asBoolean(false);
    }
}
