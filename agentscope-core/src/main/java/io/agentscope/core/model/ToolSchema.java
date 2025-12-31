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

package io.agentscope.core.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable tool schema definition.
 * Describes a tool's interface using JSON Schema for parameters.
 */
public class ToolSchema {
    private final String name;
    private final String description;
    private final Map<String, Object> parameters;
    private final Boolean strict;

    /**
     * Creates a new ToolSchema instance using the builder pattern.
     *
     * @param builder the builder containing the schema configuration
     */
    private ToolSchema(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name is required");
        this.description = Objects.requireNonNull(builder.description, "description is required");
        this.parameters =
                builder.parameters != null
                        ? Collections.unmodifiableMap(new HashMap<>(builder.parameters))
                        : Collections.emptyMap();
        this.strict = builder.strict;
    }

    /**
     * Gets the tool name.
     *
     * @return the tool name
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the tool description.
     *
     * @return the tool description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Gets the tool parameters as a JSON Schema.
     *
     * @return an unmodifiable map containing the parameter schema
     */
    public Map<String, Object> getParameters() {
        return parameters;
    }

    /**
     * Gets the strict mode flag for schema validation.
     *
     * @return true if strict mode is enabled, false otherwise, or null if not specified
     */
    public Boolean getStrict() {
        return strict;
    }

    /**
     * Creates a new builder for ToolSchema.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating ToolSchema instances.
     */
    public static class Builder {
        private String name;
        private String description;
        private Map<String, Object> parameters;
        private Boolean strict;

        /**
         * Sets the tool name.
         *
         * @param name the tool name
         * @return this builder instance
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the tool description.
         *
         * @param description the tool description
         * @return this builder instance
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets the tool parameters as a JSON Schema.
         *
         * @param parameters the parameter schema
         * @return this builder instance
         */
        public Builder parameters(Map<String, Object> parameters) {
            this.parameters = parameters;
            return this;
        }

        /**
         * Sets the strict mode for schema validation.
         *
         * @param strict whether to enable strict mode
         * @return this builder instance
         */
        public Builder strict(Boolean strict) {
            this.strict = strict;
            return this;
        }

        /**
         * Builds a new ToolSchema instance with the set values.
         *
         * @return a new ToolSchema instance
         */
        public ToolSchema build() {
            return new ToolSchema(this);
        }
    }
}
