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
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;
import com.fasterxml.jackson.module.jsonSchema.JsonSchemaGenerator;
import java.lang.reflect.Type;
import java.util.Map;

/**
 * Utility class for JSON Schema operations.
 *
 * <p>
 * This class provides utility methods for:
 * <ul>
 * <li>Generating JSON schemas from Java classes (for structured output)</li>
 * <li>Converting between Maps and typed objects</li>
 * <li>Mapping Java types to JSON Schema types</li>
 * </ul>
 *
 * @hidden
 */
public class JsonSchemaUtils {

	private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
			.addModule(new JavaTimeModule())
			.build();
    private static final JsonSchemaGenerator schemaGenerator =
            new JsonSchemaGenerator(OBJECT_MAPPER);

    /**
     * Generate JSON Schema from a Java class.
     * This method is suitable for structured output scenarios where complex nested
     * objects need to be converted to JSON Schema format.
     *
     * <p><b>Note:</b> This method does NOT support generic types like {@code List<Order>}
     * due to Java's type erasure. For generic types, use:
     * <ul>
     *   <li>{@link #generateSchemaFromType(Type)} with method return type</li>
     *   <li>{@link #generateSchemaFromTypeReference(TypeReference)} for all generic types</li>
     * </ul>
     *
     * @param clazz The class to generate schema for
     * @return JSON Schema as a Map
     * @throws RuntimeException if schema generation fails due to reflection errors,
     *                          Jackson configuration issues, or other processing
     *                          errors
     */
    public static Map<String, Object> generateSchemaFromClass(Class<?> clazz) {
        try {
            JsonSchema schema = schemaGenerator.generateSchema(clazz);
            return OBJECT_MAPPER.convertValue(schema, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JSON schema for " + clazz.getName(), e);
        }
    }

    /**
     * Generate JSON Schema from a generic type using TypeReference.
     * This method supports complex generic types including nested generics.
     *
     * <p><b>Usage Examples:</b>
     * <pre>{@code
     * // For List<Order>
     * Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromTypeReference(
     *     new TypeReference<List<Order>>() {}
     * );
     *
     * // For Map<String, List<Order>>
     * Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromTypeReference(
     *     new TypeReference<Map<String, List<Order>>>() {}
     * );
     *
     * // For nested generics: List<Map<String, Order>>
     * Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromTypeReference(
     *     new TypeReference<List<Map<String, Order>>>() {}
     * );
     * }</pre>
     *
     * @param typeReference TypeReference containing the generic type information
     * @param <T> The generic type
     * @return JSON Schema as a Map with full generic type information
     * @throws RuntimeException if schema generation fails
     */
    public static <T> Map<String, Object> generateSchemaFromTypeReference(TypeReference<T> typeReference) {
        try {
            Type type = typeReference.getType();
            JavaType javaType = OBJECT_MAPPER.constructType(type);
            JsonSchema schema = schemaGenerator.generateSchema(javaType);
            return OBJECT_MAPPER.convertValue(schema, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to generate JSON schema for type reference: " + typeReference.getType(), e);
        }
    }


    /**
     * Generate JSON Schema from a Java Type (supports Generics).
     *
     * @param type The type to generate schema for
     * @return JSON Schema as a Map
     */
    public static Map<String, Object> generateSchemaFromType(Type type) {
        try {
            JavaType javaType = OBJECT_MAPPER.constructType(type);
            JsonSchema schema = schemaGenerator.generateSchema(javaType);
            return OBJECT_MAPPER.convertValue(schema, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to generate JSON schema for " + type.getTypeName(), e);
        }
    }

    /**
     * Convert Map to typed object.
     *
     * @param data        The data map
     * @param targetClass The target class
     * @param <T>         The type
     * @return Converted object
     * @throws IllegalStateException if the input data is null
     * @throws RuntimeException      if the conversion fails due to type mismatch,
     *                               JSON parsing errors, or incompatible data
     *                               structure
     */
    public static <T> T convertToObject(Object data, Class<T> targetClass) {
        if (data == null) {
            throw new IllegalStateException("No structured data available in response");
        }

        try {
            return OBJECT_MAPPER.convertValue(data, targetClass);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert metadata to " + targetClass.getName(), e);
        }
    }

	/**
	 * Get the ObjectMapper instance.
	 * @return ObjectMapper
	 */
    public static ObjectMapper getJsonScheamObjectMapper() {
        return OBJECT_MAPPER;
    }
}
