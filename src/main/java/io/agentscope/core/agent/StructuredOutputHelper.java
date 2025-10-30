/*
 * Copyright 2024-2025 the original author or authors.
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

package io.agentscope.core.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;
import com.fasterxml.jackson.module.jsonSchema.JsonSchemaGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Helper class for structured output operations.
 *
 * <p>This class provides utility methods for generating JSON schemas from Java classes
 * and converting between Maps and typed objects.
 */
public class StructuredOutputHelper {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final JsonSchemaGenerator schemaGenerator =
            new JsonSchemaGenerator(objectMapper);

    /**
     * Generate JSON Schema from a Java class.
     *
     * @param clazz The class to generate schema for
     * @return JSON Schema as a Map
     */
    public static Map<String, Object> generateJsonSchema(Class<?> clazz) {
        try {
            JsonSchema schema = schemaGenerator.generateSchema(clazz);
            Map<String, Object> schemaMap =
                    objectMapper.convertValue(schema, new TypeReference<Map<String, Object>>() {});
            removeTitleFields(schemaMap);
            ensureRequiredFields(schemaMap);
            return schemaMap;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JSON schema for " + clazz.getName(), e);
        }
    }

    /**
     * Ensure all properties are marked as required for OpenAI strict mode.
     *
     * <p>OpenAI's strict mode requires that the 'required' array includes ALL keys from 'properties'.
     * This method recursively processes the schema to ensure this requirement is met.
     *
     * @param schema The schema map to process
     */
    @SuppressWarnings("unchecked")
    public static void ensureRequiredFields(Map<String, Object> schema) {
        // Process current level
        if (schema.containsKey("properties")) {
            Object propertiesObj = schema.get("properties");
            if (propertiesObj instanceof Map) {
                Map<String, Object> properties = (Map<String, Object>) propertiesObj;

                // Get or create required array
                List<String> required;
                Object requiredObj = schema.get("required");
                if (requiredObj instanceof List) {
                    required = new ArrayList<>((List<String>) requiredObj);
                } else {
                    required = new ArrayList<>();
                }

                // Add all property keys to required if not already present
                for (String propertyKey : properties.keySet()) {
                    if (!required.contains(propertyKey)) {
                        required.add(propertyKey);
                    }
                }

                // Update the required field
                schema.put("required", required);

                // Recursively process nested objects
                for (Object propertyValue : properties.values()) {
                    if (propertyValue instanceof Map) {
                        ensureRequiredFields((Map<String, Object>) propertyValue);
                    }
                }
            }
        }

        // Recursively process other nested structures
        for (Object value : schema.values()) {
            if (value instanceof Map) {
                Map<String, Object> nestedMap = (Map<String, Object>) value;
                // Only process if it looks like a schema (has type or properties)
                if (nestedMap.containsKey("type") || nestedMap.containsKey("properties")) {
                    ensureRequiredFields(nestedMap);
                }
            } else if (value instanceof List) {
                ((List<?>) value)
                        .forEach(
                                item -> {
                                    if (item instanceof Map) {
                                        Map<String, Object> itemMap = (Map<String, Object>) item;
                                        if (itemMap.containsKey("type")
                                                || itemMap.containsKey("properties")) {
                                            ensureRequiredFields(itemMap);
                                        }
                                    }
                                });
            }
        }
    }

    /**
     * Remove "title" fields from JSON schema (align with Python implementation).
     *
     * @param schema The schema map to clean
     */
    @SuppressWarnings("unchecked")
    public static void removeTitleFields(Map<String, Object> schema) {
        schema.remove("title");
        schema.values()
                .forEach(
                        value -> {
                            if (value instanceof Map) {
                                removeTitleFields((Map<String, Object>) value);
                            } else if (value instanceof List) {
                                ((List<?>) value)
                                        .forEach(
                                                item -> {
                                                    if (item instanceof Map) {
                                                        removeTitleFields(
                                                                (Map<String, Object>) item);
                                                    }
                                                });
                            }
                        });
    }

    /**
     * Convert Map to typed object.
     *
     * @param data The data map
     * @param targetClass The target class
     * @param <T> The type
     * @return Converted object
     */
    public static <T> T convertToObject(Object data, Class<T> targetClass) {
        if (data == null) {
            throw new IllegalStateException("No structured data available in response");
        }

        try {
            return objectMapper.convertValue(data, targetClass);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert metadata to " + targetClass.getName(), e);
        }
    }
}
