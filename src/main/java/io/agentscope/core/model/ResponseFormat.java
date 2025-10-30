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

package io.agentscope.core.model;

/**
 * Response format configuration for structured output.
 *
 * <p>This class specifies how the model should format its response. It supports
 * two main modes:
 * <ul>
 *   <li><b>JSON Object</b>: Simple JSON mode where the model returns valid JSON
 *   <li><b>JSON Schema</b>: Structured mode where the model adheres to a specific JSON Schema
 * </ul>
 *
 * <p>Usage examples:
 * <pre>{@code
 * // Simple JSON object mode
 * ResponseFormat format = ResponseFormat.jsonObject();
 *
 * // JSON Schema mode
 * JsonSchemaSpec schema = JsonSchemaSpec.builder()
 *     .name("employee_list")
 *     .schema(Map.of("type", "object", ...))
 *     .build();
 * ResponseFormat format = ResponseFormat.jsonSchema(schema);
 * }</pre>
 */
public class ResponseFormat {
    private final String type;
    private final JsonSchemaSpec jsonSchema;

    private ResponseFormat(String type, JsonSchemaSpec jsonSchema) {
        this.type = type;
        this.jsonSchema = jsonSchema;
    }

    /**
     * Gets the response format type.
     *
     * @return "json_object" or "json_schema"
     */
    public String getType() {
        return type;
    }

    /**
     * Gets the JSON Schema specification (only applicable when type is "json_schema").
     *
     * @return the JSON Schema spec, or null if not applicable
     */
    public JsonSchemaSpec getJsonSchema() {
        return jsonSchema;
    }

    /**
     * Creates a response format for simple JSON object mode.
     *
     * <p>In this mode, the model will return valid JSON but without a specific schema constraint.
     *
     * @return a ResponseFormat for JSON object mode
     */
    public static ResponseFormat jsonObject() {
        return new ResponseFormat("json_object", null);
    }

    /**
     * Creates a response format with a JSON Schema specification.
     *
     * <p>In this mode, the model will adhere to the provided JSON Schema.
     *
     * @param schema the JSON Schema specification
     * @return a ResponseFormat for JSON schema mode
     */
    public static ResponseFormat jsonSchema(JsonSchemaSpec schema) {
        if (schema == null) {
            throw new IllegalArgumentException("JsonSchemaSpec cannot be null");
        }
        return new ResponseFormat("json_schema", schema);
    }

    /**
     * Checks if this is a JSON Schema mode.
     *
     * @return true if type is "json_schema"
     */
    public boolean isJsonSchema() {
        return "json_schema".equals(type);
    }

    /**
     * Checks if this is a JSON Object mode.
     *
     * @return true if type is "json_object"
     */
    public boolean isJsonObject() {
        return "json_object".equals(type);
    }
}
