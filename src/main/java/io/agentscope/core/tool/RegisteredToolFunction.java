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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wrapper for AgentTool with metadata for group management and schema extension.
 *
 * <p>This class wraps an AgentTool and adds additional metadata such as group membership, extended
 * schema information, and MCP client association. It enables tools to be organized into groups and
 * have their schemas extended dynamically.
 */
class RegisteredToolFunction {

    private final AgentTool tool;
    private final String groupName; // null for ungrouped tools
    private final ExtendedModel extendedModel; // null if no extensions
    private final String mcpClientName; // null for non-MCP tools

    /**
     * Creates a new registered tool function with metadata.
     *
     * @param tool The underlying agent tool
     * @param groupName The tool group name (null for ungrouped tools)
     * @param extendedModel Extended model for schema extension (null if no extensions)
     * @param mcpClientName MCP client name for MCP tools (null for non-MCP tools)
     */
    public RegisteredToolFunction(
            AgentTool tool, String groupName, ExtendedModel extendedModel, String mcpClientName) {
        this.tool = tool;
        this.groupName = groupName;
        this.extendedModel = extendedModel;
        this.mcpClientName = mcpClientName;
    }

    /**
     * Gets the underlying agent tool.
     *
     * @return The agent tool instance
     */
    public AgentTool getTool() {
        return tool;
    }

    /**
     * Gets the tool group name.
     *
     * @return The group name, or null if ungrouped
     */
    public String getGroupName() {
        return groupName;
    }

    /**
     * Gets the extended model for schema extension.
     *
     * @return The extended model, or null if no extensions
     */
    public ExtendedModel getExtendedModel() {
        return extendedModel;
    }

    /**
     * Gets the MCP client name for MCP tools.
     *
     * @return The MCP client name, or null for non-MCP tools
     */
    public String getMcpClientName() {
        return mcpClientName;
    }

    /**
     * Get the extended JSON schema by merging base parameters with extended model.
     *
     * @return Merged parameter schema
     * @throws IllegalStateException if there are conflicting properties
     */
    public Map<String, Object> getExtendedParameters() {
        if (extendedModel == null) {
            return tool.getParameters();
        }
        return extendedModel.mergeWithBaseSchema(tool.getParameters());
    }

    /**
     * Interface for extended model that adds properties to tool parameters.
     */
    public interface ExtendedModel {

        /**
         * Get the additional properties to merge into base schema.
         */
        Map<String, Object> getAdditionalProperties();

        /**
         * Get the additional required fields.
         */
        List<String> getAdditionalRequired();

        /**
         * Merge this extended model with base schema from tool.
         *
         * @param baseParameters Base parameter schema from AgentTool
         * @return Merged schema
         * @throws IllegalStateException if properties conflict
         */
        default Map<String, Object> mergeWithBaseSchema(Map<String, Object> baseParameters) {
            Map<String, Object> merged = new HashMap<>(baseParameters);

            // Get base properties and required
            @SuppressWarnings("unchecked")
            Map<String, Object> baseProps =
                    (Map<String, Object>) merged.getOrDefault("properties", new HashMap<>());
            @SuppressWarnings("unchecked")
            List<String> baseRequired = (List<String>) merged.getOrDefault("required", List.of());

            // Merge properties with conflict detection
            Map<String, Object> extendedProps = getAdditionalProperties();
            Set<String> conflicts = new HashSet<>();
            for (String key : extendedProps.keySet()) {
                if (baseProps.containsKey(key)) {
                    conflicts.add(key);
                }
            }

            if (!conflicts.isEmpty()) {
                throw new IllegalStateException(
                        "Extended model has conflicting properties with base schema: " + conflicts);
            }

            Map<String, Object> mergedProps = new HashMap<>(baseProps);
            mergedProps.putAll(extendedProps);
            merged.put("properties", mergedProps);

            // Merge required arrays
            List<String> extendedRequired = getAdditionalRequired();
            if (!extendedRequired.isEmpty()) {
                Set<String> mergedRequired = new HashSet<>(baseRequired);
                mergedRequired.addAll(extendedRequired);
                merged.put("required", List.copyOf(mergedRequired));
            }

            return merged;
        }
    }

    /**
     * Simple implementation of ExtendedModel using maps.
     */
    public static class SimpleExtendedModel implements ExtendedModel {

        private final Map<String, Object> additionalProperties;
        private final List<String> additionalRequired;

        public SimpleExtendedModel(
                Map<String, Object> additionalProperties, List<String> additionalRequired) {
            this.additionalProperties = additionalProperties;
            this.additionalRequired = additionalRequired;
        }

        @Override
        public Map<String, Object> getAdditionalProperties() {
            return additionalProperties;
        }

        @Override
        public List<String> getAdditionalRequired() {
            return additionalRequired;
        }
    }
}
