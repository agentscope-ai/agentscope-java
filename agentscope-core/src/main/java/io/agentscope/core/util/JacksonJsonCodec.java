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

package io.agentscope.core.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jackson-based implementation of {@link JsonCodec}.
 *
 * <p>This is the default implementation used by {@link JsonUtils}. It uses
 * Jackson's ObjectMapper with the following configuration:
 * <ul>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES = false} - allows unknown fields in JSON</li>
 * </ul>
 *
 * <p>Users can access the underlying ObjectMapper via {@link #getObjectMapper()}
 * for advanced operations not covered by the JsonCodec interface.
 *
 * @see JsonCodec
 * @see JsonUtils
 */
public class JacksonJsonCodec implements JsonCodec {

    private static final Logger log = LoggerFactory.getLogger(JacksonJsonCodec.class);

    private final ObjectMapper objectMapper;

    /**
     * Creates a new JacksonJsonCodec with default ObjectMapper configuration.
     */
    public JacksonJsonCodec() {
        this.objectMapper = createDefaultObjectMapper();
    }

    /**
     * Creates a new JacksonJsonCodec with a custom ObjectMapper.
     *
     * @param objectMapper the ObjectMapper to use
     */
    public JacksonJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Creates the default ObjectMapper with standard configuration.
     *
     * @return configured ObjectMapper
     */
    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    /**
     * Get the underlying ObjectMapper for advanced operations.
     *
     * @return the ObjectMapper instance
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    /**
     * Converts an object to a Map with null values sanitized for use as tool arguments.
     *
     * <p>Null values are replaced with empty strings, and empty nested maps are also
     * replaced with empty strings to prevent serialization issues with APIs that don't
     * handle null values in JSON objects.
     *
     * @param obj the object to convert and sanitize
     * @return a sanitized map with null values replaced by empty strings
     */
    public Map<String, Object> toMapWithSanitizedNulls(Object obj) {
        if (obj == null) {
            return Collections.emptyMap();
        }
        if (obj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> originalMap = (Map<String, Object>) obj;
            return sanitizeToolArgumentsMap(originalMap);
        }
        // Try to convert to map
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> converted = convertValue(obj, Map.class);
            return sanitizeToolArgumentsMap(converted);
        } catch (JsonException e) {
            return Collections.emptyMap();
        }
    }

    /**
     * Recursively sanitizes a map for use as tool arguments.
     *
     * @param input the input map to sanitize
     * @return a new map with null values replaced by empty strings
     */
    private Map<String, Object> sanitizeToolArgumentsMap(Map<String, Object> input) {
        if (input == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                // Replace null with empty string
                result.put(entry.getKey(), "");
            } else if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) value;
                if (nestedMap.isEmpty()) {
                    // Replace empty nested map with empty string
                    result.put(entry.getKey(), "");
                } else {
                    result.put(entry.getKey(), sanitizeToolArgumentsMap(nestedMap));
                }
            } else if (value instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) value;
                result.put(entry.getKey(), sanitizeToolArgumentsList(list));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    /**
     * Recursively sanitizes a list for use as tool arguments.
     *
     * @param input the input list to sanitize
     * @return a new list with null values replaced by empty strings
     */
    private List<Object> sanitizeToolArgumentsList(List<Object> input) {
        if (input == null) {
            return Collections.emptyList();
        }
        List<Object> result = new ArrayList<>(input.size());
        for (Object item : input) {
            if (item == null) {
                result.add("");
            } else if (item instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) item;
                if (nestedMap.isEmpty()) {
                    result.add("");
                } else {
                    result.add(sanitizeToolArgumentsMap(nestedMap));
                }
            } else if (item instanceof List) {
                result.add(sanitizeToolArgumentsList((List<Object>) item));
            } else {
                result.add(item);
            }
        }
        return result;
    }

    @Override
    public String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object to JSON: {}", e.getMessage(), e);
            throw new JsonException("Failed to serialize object to JSON", e);
        }
    }

    @Override
    public String toPrettyJson(Object obj) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object to pretty JSON: {}", e.getMessage(), e);
            throw new JsonException("Failed to serialize object to pretty JSON", e);
        }
    }

    @Override
    public <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new JsonException("Failed to deserialize JSON to " + type.getName(), e);
        }
    }

    @Override
    public <T> T fromJson(String json, TypeReference<T> typeRef) {
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new JsonException("Failed to deserialize JSON", e);
        }
    }

    @Override
    public <T> T convertValue(Object from, Class<T> toType) {
        try {
            return objectMapper.convertValue(from, toType);
        } catch (IllegalArgumentException e) {
            throw new JsonException("Failed to convert value to " + toType.getName(), e);
        }
    }

    @Override
    public <T> T convertValue(Object from, TypeReference<T> toTypeRef) {
        try {
            return objectMapper.convertValue(from, toTypeRef);
        } catch (IllegalArgumentException e) {
            throw new JsonException("Failed to convert value", e);
        }
    }

    @Override
    public Object convertValue(Object from, Type toType) {
        try {
            JavaType javaType = objectMapper.getTypeFactory().constructType(toType);
            return objectMapper.convertValue(from, javaType);
        } catch (IllegalArgumentException e) {
            throw new JsonException("Failed to convert value to " + toType.getTypeName(), e);
        }
    }
}
