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

package io.agentscope.core.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;
import com.fasterxml.jackson.module.jsonSchema.JsonSchemaGenerator;
import java.util.List;
import java.util.Map;

/**
 * Utility class for JSON Schema operations.
 *
 * <p>This class provides utility methods for:
 * <ul>
 *   <li>Generating JSON schemas from Java classes (for structured output)</li>
 *   <li>Converting between Maps and typed objects</li>
 *   <li>Mapping Java types to JSON Schema types</li>
 * </ul>
 */
public class JsonSchemaUtils {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final JsonSchemaGenerator schemaGenerator =
            new JsonSchemaGenerator(objectMapper);

    /**
     * Generate JSON Schema from a Java class.
     * This method is suitable for structured output scenarios where complex nested objects
     * need to be converted to JSON Schema format.
     *
     * @param clazz The class to generate schema for
     * @return JSON Schema as a Map
     * @throws RuntimeException if schema generation fails due to reflection errors,
     *         Jackson configuration issues, or other processing errors
     * @since 1.0
     */
    public static Map<String, Object> generateSchemaFromClass(Class<?> clazz) {
        try {
            JsonSchema schema = schemaGenerator.generateSchema(clazz);
            return objectMapper.convertValue(schema, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JSON schema for " + clazz.getName(), e);
        }
    }

    /**
     * Convert Map to typed object.
     *
     * @param data The data map
     * @param targetClass The target class
     * @param <T> The type
     * @return Converted object
     * @throws IllegalStateException if the input data is null
     * @throws RuntimeException if the conversion fails due to type mismatch,
     *         JSON parsing errors, or incompatible data structure
     * @since 1.0
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

    /**
     * Map Java type to JSON Schema type.
     * This is a simple type mapping suitable for basic tool parameters.
     *
     * @param clazz the Java class
     * @return JSON type string
     * @since 1.0
     */
    public static String mapJavaTypeToJsonType(Class<?> clazz) {
        if (clazz == String.class) {
            return "string";
        } else if (clazz == Integer.class
                || clazz == int.class
                || clazz == Long.class
                || clazz == long.class) {
            return "integer";
        } else if (clazz == Double.class
                || clazz == double.class
                || clazz == Float.class
                || clazz == float.class) {
            return "number";
        } else if (clazz == Boolean.class || clazz == boolean.class) {
            return "boolean";
        } else if (clazz.isArray() || List.class.isAssignableFrom(clazz)) {
            return "array";
        } else {
            return "object";
        }
    }
}
