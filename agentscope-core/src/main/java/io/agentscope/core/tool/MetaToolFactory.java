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

import io.agentscope.core.message.ToolResultBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Factory for creating meta tools that allow agents to dynamically manage tool groups.
 */
class MetaToolFactory {

    private final ToolGroupManager groupManager;
    private final ToolRegistry toolRegistry;

    MetaToolFactory(ToolGroupManager groupManager, ToolRegistry toolRegistry) {
        this.groupManager = groupManager;
        this.toolRegistry = toolRegistry;
    }

    /**
     * Create the reset_equipped_tools meta tool.
     *
     * @return AgentTool for reset_equipped_tools
     */
    AgentTool createResetEquippedToolsAgentTool() {
        return new AgentTool() {
            @Override
            public String getName() {
                return "reset_equipped_tools";
            }

            @Override
            public String getDescription() {
                // CRITICAL: Description must clearly explain the tool's functionality
                return "Reset the equipped tools by activating specified tool groups.\n\n"
                        + groupManager.getNotes();
            }

            @Override
            public Map<String, Object> getParameters() {
                // Build schema dynamically based on available tool groups
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");

                // Properties
                Map<String, Object> properties = new HashMap<>();
                Map<String, Object> toActivateParam = new HashMap<>();
                toActivateParam.put("type", "array");

                Map<String, Object> items = new HashMap<>();
                items.put("type", "string");

                // Generate enum from available tool groups
                List<String> availableGroups = new ArrayList<>(groupManager.getToolGroupNames());
                if (!availableGroups.isEmpty()) {
                    items.put("enum", availableGroups);
                }

                toActivateParam.put("items", items);
                toActivateParam.put("description", "The list of tool group names to activate.");

                properties.put("to_activate", toActivateParam);
                schema.put("properties", properties);

                // Required fields
                schema.put("required", List.of("to_activate"));

                return schema;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                try {
                    @SuppressWarnings("unchecked")
                    List<String> toActivate = (List<String>) param.getInput().get("to_activate");

                    if (toActivate == null) {
                        return Mono.just(
                                ToolResultBlock.error("Missing required parameter: to_activate"));
                    }

                    String result = resetEquippedToolsImpl(toActivate);
                    return Mono.just(ToolResultBlock.text(result));
                } catch (Exception e) {
                    return Mono.just(ToolResultBlock.error(e.getMessage()));
                }
            }
        };
    }

    /**
     * Implementation of reset_equipped_tools logic.
     *
     * CRITICAL SEMANTICS: Only activates specified groups, does NOT deactivate others.
     *
     * @param toActivate List of tool group names to activate
     * @return Success message describing activated tools
     * @throws IllegalArgumentException if any group doesn't exist
     */
    private String resetEquippedToolsImpl(List<String> toActivate) {
        // Validate all groups exist
        for (String groupName : toActivate) {
            groupManager.validateGroupExists(groupName);
        }

        // Activate groups (only calls update with active=True)
        groupManager.updateToolGroups(toActivate, true);

        // Build response message
        StringBuilder result = new StringBuilder();
        result.append("Successfully activated tool groups: ").append(toActivate).append("\n\n");

        // List activated tools
        result.append("Activated tools:\n");
        for (String groupName : toActivate) {
            ToolGroup group = groupManager.getToolGroup(groupName);
            result.append(String.format("- Group '%s': %s\n", groupName, group.getDescription()));
            for (String toolName : group.getTools()) {
                AgentTool tool = toolRegistry.getTool(toolName);
                if (tool != null) {
                    result.append(String.format("  - %s: %s\n", toolName, tool.getDescription()));
                }
            }
        }

        return result.toString();
    }
}
