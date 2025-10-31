/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.tool;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a named group of tools with activation state.
 */
public class ToolGroup {

    private final String name;
    private final String description;
    private boolean active;
    private final Set<String> tools; // Tool names in this group

    private ToolGroup(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name cannot be null");
        this.description = builder.description != null ? builder.description : "";
        this.active = builder.active;
        this.tools = new HashSet<>(builder.tools);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Set<String> getTools() {
        return new HashSet<>(tools); // Defensive copy
    }

    public void addTool(String toolName) {
        tools.add(toolName);
    }

    public void removeTool(String toolName) {
        tools.remove(toolName);
    }

    public boolean containsTool(String toolName) {
        return tools.contains(toolName);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private String name;
        private String description = "";
        private boolean active = true;
        private Set<String> tools = new HashSet<>();

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder active(boolean active) {
            this.active = active;
            return this;
        }

        public Builder tools(Set<String> tools) {
            this.tools = new HashSet<>(tools);
            return this;
        }

        public ToolGroup build() {
            return new ToolGroup(this);
        }
    }
}
