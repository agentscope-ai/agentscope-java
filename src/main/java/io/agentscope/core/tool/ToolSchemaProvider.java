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

import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides tool schemas in various formats for model consumption.
 * Responsible for filtering tools based on active groups and formatting schemas.
 */
class ToolSchemaProvider {

    private final ToolRegistry toolRegistry;
    private final ToolGroupManager groupManager;

    ToolSchemaProvider(ToolRegistry toolRegistry, ToolGroupManager groupManager) {
        this.toolRegistry = toolRegistry;
        this.groupManager = groupManager;
    }

    /**
     * Get tool schemas in OpenAI format, respecting active tool groups.
     *
     * @return List of tool schemas in OpenAI format
     */
    List<Map<String, Object>> getToolSchemas() {
        List<Map<String, Object>> schemas = new ArrayList<>();

        for (RegisteredToolFunction registered : toolRegistry.getAllRegisteredTools().values()) {
            AgentTool tool = registered.getTool();
            String groupName = registered.getGroupName();

            // Filter: Only include if ungrouped OR in active group
            if (!groupManager.isInActiveGroup(groupName)) {
                continue; // Skip inactive tools
            }

            Map<String, Object> schema = new HashMap<>();
            schema.put("type", "function");

            Map<String, Object> function = new HashMap<>();
            function.put("name", tool.getName());
            function.put("description", tool.getDescription());

            // Use extended parameters if available
            function.put("parameters", registered.getExtendedParameters());

            schema.put("function", function);
            schemas.add(schema);
        }

        return schemas;
    }

    /**
     * Get tool schemas as ToolSchema objects for model consumption.
     * Updated to respect active tool groups.
     *
     * @return List of ToolSchema objects
     */
    List<ToolSchema> getToolSchemasForModel() {
        List<ToolSchema> schemas = new ArrayList<>();

        for (RegisteredToolFunction registered : toolRegistry.getAllRegisteredTools().values()) {
            AgentTool tool = registered.getTool();
            String groupName = registered.getGroupName();

            // Filter: Only include if ungrouped OR in active group
            if (!groupManager.isInActiveGroup(groupName)) {
                continue; // Skip inactive tools
            }

            ToolSchema schema =
                    ToolSchema.builder()
                            .name(tool.getName())
                            .description(tool.getDescription())
                            .parameters(registered.getExtendedParameters())
                            .build();
            schemas.add(schema);
        }

        return schemas;
    }
}
