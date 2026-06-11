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

import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Provides tool schemas in various formats for model consumption.
 *
 * <p>This class is responsible for generating tool schemas that are sent to LLMs to inform them
 * about available tools. It filters tools based on active tool groups, ensuring that only tools
 * from active groups (or ungrouped tools) are included in the schemas.
 *
 * <p><b>Key Responsibilities:</b>
 * <ul>
 *   <li>Generate tool schemas in OpenAI format (Map-based representation)</li>
 *   <li>Generate tool schemas as {@link ToolSchema} objects for model APIs</li>
 *   <li>Filter tools based on {@link ToolGroupManager} activation state</li>
 *   <li>Use extended parameters from {@link RegisteredToolFunction} for accurate schemas</li>
 * </ul>
 *
 * <p><b>Filtering Logic:</b> A tool is included in schemas if and only if:
 * <ul>
 *   <li>It is ungrouped (not assigned to any tool group), OR</li>
 *   <li>It belongs to at least one group that is currently active</li>
 * </ul>
 */
class ToolSchemaProvider {

    private final ToolRegistry toolRegistry;
    private final ToolGroupManager groupManager;

    /**
     * Creates a ToolSchemaProvider with the given registry and group manager.
     *
     * @param toolRegistry The tool registry to retrieve registered tools from
     * @param groupManager The group manager to check tool group activation status
     */
    ToolSchemaProvider(ToolRegistry toolRegistry, ToolGroupManager groupManager) {
        this.toolRegistry = toolRegistry;
        this.groupManager = groupManager;
    }

    /**
     * Get tool schemas as ToolSchema objects for model consumption.
     * Updated to respect active tool groups.
     *
     * @return List of ToolSchema objects
     */
    List<ToolSchema> getToolSchemas() {
        return buildSchemas(groupManager.getActiveToolNames());
    }

    /**
     * Get tool schemas filtered by an explicitly supplied set of active group names, independent
     * of the shared per-group activation flags. Per-call / stateless variant of
     * {@link #getToolSchemas()}.
     *
     * @param activeGroups the group names to treat as active
     * @return List of ToolSchema objects visible for the supplied groups (plus all ungrouped tools)
     */
    List<ToolSchema> getToolSchemas(Collection<String> activeGroups) {
        return buildSchemas(groupManager.getActiveToolNames(activeGroups));
    }

    private List<ToolSchema> buildSchemas(Set<String> activeTools) {
        List<ToolSchema> schemas = new ArrayList<>();
        List<RegisteredToolFunction> registeredTools =
                new ArrayList<>(toolRegistry.getAllRegisteredTools().values());

        for (RegisteredToolFunction registered : registeredTools) {
            AgentTool tool = registered.getTool();
            String toolName = tool.getName();

            // Filter: include ungrouped tools, or tools in any active group
            if (groupManager.isGroupedTool(toolName) && !activeTools.contains(toolName)) {
                continue; // Skip inactive grouped tools
            }

            ToolSchema schema =
                    ToolSchema.builder()
                            .name(toolName)
                            .description(tool.getDescription())
                            .title(tool.getTitle())
                            .parameters(registered.getExtendedParameters())
                            .strict(tool.getStrict())
                            .outputSchema(tool.getOutputSchema())
                            .build();
            schemas.add(schema);
        }

        return schemas;
    }
}
