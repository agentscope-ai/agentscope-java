/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

import java.util.Map;

public class ToolSchema {
    private String name;
    private String description;
    private Map<String, Object> parameters; // JSON Schema

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

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ToolSchema s = new ToolSchema();

        public Builder name(String name) {
            s.setName(name);
            return this;
        }

        public Builder description(String desc) {
            s.setDescription(desc);
            return this;
        }

        public Builder parameters(Map<String, Object> params) {
            s.setParameters(params);
            return this;
        }

        public ToolSchema build() {
            return s;
        }
    }
}
