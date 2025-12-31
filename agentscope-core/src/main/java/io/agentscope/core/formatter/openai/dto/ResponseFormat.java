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
package io.agentscope.core.formatter.openai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response format configuration for OpenAI API.
 *
 * <p>This class supports three response format types:
 * <ul>
 *   <li><b>text</b>: Plain text response (default)</li>
 *   <li><b>json_object</b>: Valid JSON object response</li>
 *   <li><b>json_schema</b>: Response conforming to a specific JSON Schema</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Plain text
 * ResponseFormat format = ResponseFormat.text();
 *
 * // JSON object
 * ResponseFormat format = ResponseFormat.jsonObject();
 *
 * // JSON Schema with strict validation
 * JsonSchema schema = JsonSchema.builder()
 *     .name("MathResponse")
 *     .schema(Map.of("type", "object", ...))
 *     .strict(true)
 *     .build();
 * ResponseFormat format = ResponseFormat.jsonSchema(schema);
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseFormat {

    /** The type of response format: "text", "json_object", or "json_schema". */
    @JsonProperty("type")
    private String type;

    /** JSON Schema specification (only for json_schema type). */
    @JsonProperty("json_schema")
    private JsonSchema jsonSchema;

    public ResponseFormat() {}

    private ResponseFormat(String type, JsonSchema jsonSchema) {
        this.type = type;
        this.jsonSchema = jsonSchema;
    }

    /**
     * Create a plain text response format.
     *
     * @return ResponseFormat configured for plain text
     */
    public static ResponseFormat text() {
        return new ResponseFormat("text", null);
    }

    /**
     * Create a JSON object response format.
     *
     * <p>The model will return a valid JSON object, but without
     * a specific schema validation.
     *
     * @return ResponseFormat configured for JSON object
     */
    public static ResponseFormat jsonObject() {
        return new ResponseFormat("json_object", null);
    }

    /**
     * Create a JSON Schema response format with structured output validation.
     *
     * <p>The model will return a response that conforms to the provided
     * JSON Schema. When strict mode is enabled, the response will be
     * validated against the schema.
     *
     * @param schema The JSON Schema defining the expected structure
     * @return ResponseFormat configured for JSON Schema validation
     */
    public static ResponseFormat jsonSchema(JsonSchema schema) {
        return new ResponseFormat("json_schema", schema);
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public JsonSchema getJsonSchema() {
        return jsonSchema;
    }

    public void setJsonSchema(JsonSchema jsonSchema) {
        this.jsonSchema = jsonSchema;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String type;
        private JsonSchema jsonSchema;

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder jsonSchema(JsonSchema jsonSchema) {
            this.type = "json_schema";
            this.jsonSchema = jsonSchema;
            return this;
        }

        public ResponseFormat build() {
            return new ResponseFormat(type, jsonSchema);
        }
    }
}
