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
import java.util.Map;

/**
 * JSON Schema definition for structured outputs.
 *
 * <p>This class represents a JSON Schema that defines the structure
 * of the expected response from the model.
 *
 * <p>Example usage:
 * <pre>{@code
 * JsonSchema schema = JsonSchema.builder()
 *     .name("MathResponse")
 *     .description("Response with answer and steps")
 *     .schema(Map.of(
 *         "type", "object",
 *         "properties", Map.of(
 *             "answer", Map.of("type", "number"),
 *             "steps", Map.of("type", "array", "items", Map.of("type", "string"))
 *         ),
 *         "required", List.of("answer", "steps"),
 *         "additionalProperties", false
 *     ))
 *     .strict(true)
 *     .build();
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonSchema {

    /** The name of the schema. */
    @JsonProperty("name")
    private String name;

    /** Optional description of what the schema represents. */
    @JsonProperty("description")
    private String description;

    /** The JSON Schema definition. */
    @JsonProperty("schema")
    private Map<String, Object> schema;

    /** Whether to enable strict mode for schema validation. */
    @JsonProperty("strict")
    private Boolean strict;

    public JsonSchema() {}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getSchema() {
        return schema;
    }

    public void setSchema(Map<String, Object> schema) {
        this.schema = schema;
    }

    public Boolean getStrict() {
        return strict;
    }

    public void setStrict(Boolean strict) {
        this.strict = strict;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final JsonSchema schema = new JsonSchema();

        public Builder name(String name) {
            schema.setName(name);
            return this;
        }

        public Builder description(String description) {
            schema.setDescription(description);
            return this;
        }

        public Builder schema(Map<String, Object> schemaMap) {
            schema.setSchema(schemaMap);
            return this;
        }

        public Builder strict(Boolean strict) {
            schema.setStrict(strict);
            return this;
        }

        public JsonSchema build() {
            return schema;
        }
    }
}
