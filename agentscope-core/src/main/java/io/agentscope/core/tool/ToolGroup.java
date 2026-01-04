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
package io.agentscope.core.tool;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a named group of tools with activation state.
 *
 * <p>Tool groups allow organizing tools into logical categories and controlling their availability
 * dynamically. Only tools from active groups are made available to agents.
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * ToolGroup adminGroup = ToolGroup.builder()
 *     .name("admin")
 *     .description("Administrative tools")
 *     .active(false) // Start inactive
 *     .build();
 *
 * adminGroup.addTool("delete_user");
 * adminGroup.addTool("modify_permissions");
 * adminGroup.setActive(true); // Activate when needed
 * }</pre>
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

    /**
     * Gets the name of this tool group.
     *
     * @return The group name (never null)
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the description of this tool group.
     *
     * @return The group description (empty string if not set)
     */
    public String getDescription() {
        return description;
    }

    /**
     * Checks if this tool group is currently active.
     *
     * @return true if active, false otherwise
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Sets the activation state of this tool group.
     *
     * @param active true to activate, false to deactivate
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Gets a defensive copy of the tools in this group.
     *
     * @return A new set containing the tool names
     */
    public Set<String> getTools() {
        return new HashSet<>(tools); // Defensive copy
    }

    /**
     * Adds a tool to this group.
     *
     * @param toolName The name of the tool to add
     */
    public void addTool(String toolName) {
        tools.add(toolName);
    }

    /**
     * Removes a tool from this group.
     *
     * @param toolName The name of the tool to remove
     */
    public void removeTool(String toolName) {
        tools.remove(toolName);
    }

    /**
     * Checks if this group contains a specific tool.
     *
     * @param toolName The tool name to check
     * @return true if the tool is in this group, false otherwise
     */
    public boolean containsTool(String toolName) {
        return tools.contains(toolName);
    }

    /**
     * Creates a new builder for constructing ToolGroup instances.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for constructing ToolGroup instances. */
    public static class Builder {

        private String name;
        private String description = "";
        private boolean active = true;
        private Set<String> tools = new HashSet<>();

        /**
         * Sets the name of the tool group.
         *
         * @param name The group name (required)
         * @return This builder for method chaining
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the description of the tool group.
         *
         * @param description The group description
         * @return This builder for method chaining
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets the initial activation state.
         *
         * @param active true for active (default), false for inactive
         * @return This builder for method chaining
         */
        public Builder active(boolean active) {
            this.active = active;
            return this;
        }

        /**
         * Sets the initial set of tools in this group.
         *
         * @param tools The tool names (a defensive copy will be made)
         * @return This builder for method chaining
         */
        public Builder tools(Set<String> tools) {
            this.tools = new HashSet<>(tools);
            return this;
        }

        /**
         * Builds a new ToolGroup with the configured settings.
         *
         * @return A new ToolGroup instance
         * @throws NullPointerException if name is null
         */
        public ToolGroup build() {
            return new ToolGroup(this);
        }
    }
}
