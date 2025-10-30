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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Specification for JSON Schema used in structured output.
 *
 * <p>This class encapsulates a JSON Schema definition that can be used to constrain
 * the output format of LLM responses. The schema is represented as a flexible Map
 * structure to support arbitrary JSON Schema specifications.
 */
public class JsonSchemaSpec {
    private final String name;
    private final Map<String, Object> schema;
    private final Boolean strict;

    private JsonSchemaSpec(Builder builder) {
        this.name = builder.name;
        this.schema =
                builder.schema != null
                        ? Collections.unmodifiableMap(new HashMap<>(builder.schema))
                        : Collections.emptyMap();
        this.strict = builder.strict;
    }

    /**
     * Gets the name of the schema.
     *
     * @return the schema name
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the JSON Schema definition.
     *
     * @return an immutable map representing the JSON Schema
     */
    public Map<String, Object> getSchema() {
        return schema;
    }

    /**
     * Gets whether strict mode is enabled.
     *
     * <p>When strict mode is enabled (OpenAI specific), the model will strictly adhere
     * to the schema and reject any output that doesn't match.
     *
     * @return true if strict mode is enabled, null if not specified
     */
    public Boolean getStrict() {
        return strict;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private Map<String, Object> schema;
        private Boolean strict = true;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder schema(Map<String, Object> schema) {
            this.schema = schema;
            return this;
        }

        /**
         * Sets whether to enable strict mode (default: true).
         *
         * <p>In strict mode, the model will strictly adhere to the schema.
         * This is an OpenAI-specific feature.
         *
         * @param strict true to enable strict mode
         * @return this builder
         */
        public Builder strict(Boolean strict) {
            this.strict = strict;
            return this;
        }

        public JsonSchemaSpec build() {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Schema name is required");
            }
            if (schema == null || schema.isEmpty()) {
                throw new IllegalArgumentException("Schema definition is required");
            }
            return new JsonSchemaSpec(this);
        }
    }
}
