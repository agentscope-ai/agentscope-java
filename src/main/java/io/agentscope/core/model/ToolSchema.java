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
import java.util.Objects;

/**
 * Immutable tool schema definition.
 * Describes a tool's interface using JSON Schema for parameters.
 */
public class ToolSchema {
    private final String name;
    private final String description;
    private final Map<String, Object> parameters;

    private ToolSchema(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name is required");
        this.description = Objects.requireNonNull(builder.description, "description is required");
        this.parameters =
                builder.parameters != null
                        ? Collections.unmodifiableMap(new HashMap<>(builder.parameters))
                        : Collections.emptyMap();
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String description;
        private Map<String, Object> parameters;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder parameters(Map<String, Object> parameters) {
            this.parameters = parameters;
            return this;
        }

        public ToolSchema build() {
            return new ToolSchema(this);
        }
    }
}
