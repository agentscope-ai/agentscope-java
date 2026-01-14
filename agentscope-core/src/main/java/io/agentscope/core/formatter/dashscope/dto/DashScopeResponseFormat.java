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
package io.agentscope.core.formatter.dashscope.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The configuration for the llm response format.
 * This class supports three types: 'text', 'json_object', and 'json_schema'.
 * <p>Example JSON:
 * <pre>{@code
 * {
 *     "model": "qwen-plus",
 *     "input": {
 *         "messages": [
 *             {
 *                 "role": "system",
 *                 "content": "Please extract the user's name and age information and return it in JSON format"
 *             },
 *             {
 *                 "role": "user",
 *                 "content": "Hello everyone, my name is Mike, I am 20 years old, my email address is mike@example.com, and I usually like to play basketball and travel"
 *             }
 *         ]
 *     },
 *     "parameters": {
 *         "result_format": "message",
 *         "response_format": {
 *             "type": "json_schema",
 *             "json_schema": {
 *                 "type": "json_schema",
 *                 "json_schema": {
 *                     "name": "user_info",
 *                     "strict": true,
 *                     "schema": {
 *                         "type": "object",
 *                         "properties": {
 *                             "name": {
 *                                 "type": "string",
 *                                 "description": "The user name"
 *                             },
 *                             "age": {
 *                                 "type": "integer",
 *                                 "description": "The user age"
 *                             },
 *                             "email": {
 *                                 "type": "string",
 *                                 "description": "The email address"
 *                             }
 *                         },
 *                         "required": [
 *                             "name",
 *                             "age"
 *                         ],
 *                         "additionalProperties": false
 *                     }
 *                 }
 *             }
 *         }
 *     }
 * }
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashScopeResponseFormat {

    /**
     * The response format type. Default value is 'text'.
     * <ul>
     *     <li>'text': output text reply</li>
     *     <li>'json_object': output a JSON string in standard format</li>
     *     <li>'json_schema': Output a JSON string in the specified format</li>
     * </ul>
     */
    @JsonProperty("type")
    private Type type = Type.TEXT;

    /**
     * Configuration for structured JSON output when {@link #type} is 'json_schema'.
     * This field is required if {@link #type} is 'json_schema'; otherwise, it should be null.
     */
    @JsonProperty("json_schema")
    private JsonSchemaConfig jsonScheme;

    /**
     * Gets the response format type.
     *
     * @return the format type
     */
    public Type getType() {
        return type;
    }

    /**
     * Sets the response format type.
     *
     * @param type the format type
     */
    public void setType(Type type) {
        this.type = type;
    }

    /**
     * Gets the JSON schema configuration.
     *
     * @return the JSON schema config, or null if not applicable
     */
    public JsonSchemaConfig getJsonScheme() {
        return jsonScheme;
    }

    /**
     * Sets the JSON schema configuration.
     *
     * @param jsonScheme the schema definition; required when type is 'json_schema'
     */
    public void setJsonScheme(JsonSchemaConfig jsonScheme) {
        this.jsonScheme = jsonScheme;
    }

    /**
     * Gets a new builder for constructing instances of {@link DashScopeResponseFormat}.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for constructing instances of {@link DashScopeResponseFormat}.
     */
    public static class Builder {

        private Type type = Type.TEXT;

        private JsonSchemaConfig jsonSchema;

        /**
         * Sets the response format type.
         *
         * @param type the format type
         * @return this builder instance
         */
        public Builder type(Type type) {
            this.type = type;
            return this;
        }

        /**
         * Sets the JSON schema configuration.
         *
         * @param jsonSchema the JSON schema configuration; required when type is 'json_schema'
         * @return this builder instance
         */
        public Builder jsonSchema(JsonSchemaConfig jsonSchema) {
            this.jsonSchema = jsonSchema;
            return this;
        }

        /**
         * Builds a new instance of {@link DashScopeResponseFormat}.
         *
         * @return a new instance of {@link DashScopeResponseFormat}
         */
        public DashScopeResponseFormat build() {
            DashScopeResponseFormat responseFormat = new DashScopeResponseFormat();
            responseFormat.setType(type);
            responseFormat.setJsonScheme(jsonSchema);
            return responseFormat;
        }
    }

    /**
     * The response format type enum.
     */
    public enum Type {

        /**
         * Output text reply.
         */
        @JsonProperty("text")
        TEXT,

        /**
         * Output a JSON string in standard format.
         */
        @JsonProperty("json_object")
        JSON_OBJECT,

        /**
         * Output a JSON string in the specified format.
         */
        @JsonProperty("json_schema")
        JSON_SCHEMA
    }

    /**
     * Represents the configuration for a JSON Schema-based structured output.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JsonSchemaConfig {

        /**
         * The schema name.
         */
        @JsonProperty("name")
        private String name;

        /**
         * The schema description.
         */
        @JsonProperty("description")
        private String description;

        /**
         * The JSON Schema object defining the output structure.
         */
        @JsonProperty("schema")
        private Object schema;

        /**
         * The strict mode to the JSON Schema. Default value is false.
         * <ul>
         *     <li>true(recommended): strict mode</li>
         *     <li>false(not recommended): non-strict mode</li>
         * </ul>
         */
        @JsonProperty("strict")
        private Boolean strict = false;

        /**
         * Gets the schema name.
         *
         * @return the schema name
         */
        public String getName() {
            return name;
        }

        /**
         * Sets the schema name.
         *
         * @param name the schema name
         */
        public void setName(String name) {
            this.name = name;
        }

        /**
         * Gets the schema description.
         *
         * @return the schema description
         */
        public String getDescription() {
            return description;
        }

        /**
         * Sets the schema description.
         *
         * @param description the schema description
         */
        public void setDescription(String description) {
            this.description = description;
        }

        /**
         * Gets the JSON Schema object defining the output structure.
         *
         * @return the schema object
         */
        public Object getSchema() {
            return schema;
        }

        /**
         * Sets the JSON Schema object defining the output structure.
         *
         * @param schema the JSON Schema object
         */
        public void setSchema(Object schema) {
            this.schema = schema;
        }

        /**
         * Gets the JSON Schema strict mode.
         *
         * @return true strict mode is enabled; false or null is disabled
         */
        public Boolean getStrict() {
            return strict;
        }

        /**
         * Sets the JSON Schema strict mode.
         *
         * @param strict true to enable strict mode (recommended), false to disable
         */
        public void setStrict(Boolean strict) {
            this.strict = strict;
        }

        /**
         * Returns a new builder for constructing instances of {@link JsonSchemaConfig}.
         *
         * @return a new builder instance
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Builder class for constructing instances of {@link JsonSchemaConfig}.
         */
        public static class Builder {

            private String name;

            private String description;

            private Object schema;

            private Boolean strict = false;

            /**
             * Sets the schema name.
             *
             * @param name the schema name
             * @return this builder instance
             */
            public Builder name(String name) {
                this.name = name;
                return this;
            }

            /**
             * Sets the schema description.
             *
             * @param description the schema description
             * @return this builder instance
             */
            public Builder description(String description) {
                this.description = description;
                return this;
            }

            /**
             * Sets the JSON Schema object.
             *
             * @param schema the JSON Schema object
             * @return this builder instance
             */
            public Builder schema(Object schema) {
                this.schema = schema;
                return this;
            }

            /**
             * Sets the strict mode to the JSON Schema.
             *
             * @param strict true to enable strict mode (recommended), false to disable
             * @return this builder instance
             */
            public Builder strict(Boolean strict) {
                this.strict = strict;
                return this;
            }

            /**
             * Builds a new instance of {@link JsonSchemaConfig}.
             *
             * @return a new instance of {@link JsonSchemaConfig}
             */
            public JsonSchemaConfig build() {
                JsonSchemaConfig jsonSchemaConfig = new JsonSchemaConfig();
                jsonSchemaConfig.setName(name);
                jsonSchemaConfig.setDescription(description);
                jsonSchemaConfig.setSchema(schema);
                jsonSchemaConfig.setStrict(strict);
                return jsonSchemaConfig;
            }
        }
    }
}
