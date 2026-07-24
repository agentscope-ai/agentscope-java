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
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

/**
 * Factory for creating meta tools that allow agents to dynamically manage tool groups.
 */
class MetaToolFactory {

    static final String RESET_EQUIPPED_TOOLS_NAME = "reset_equipped_tools";

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
        return new ResetEquippedToolsAgentTool() {
            @Override
            public String getName() {
                return RESET_EQUIPPED_TOOLS_NAME;
            }

            @Override
            public String getDescription() {
                return "Reset your equipped tools based on your current task requirements. "
                        + "These tools are organized into different groups, and you can "
                        + "activate/deactivate them by specifying which groups to keep "
                        + "active.\n\n"
                        + "**Important: The input list is the FINAL set of active tool "
                        + "groups, not incremental changes.** Any group not included in "
                        + "the list will be deactivated, regardless of its previous "
                        + "state.\n\n"
                        + "**Best practice**: Activate only what you need for the current "
                        + "task, and promptly deactivate groups as soon as they are no "
                        + "longer needed to conserve context space.\n\n"
                        + groupManager.getNotes();
            }

            @Override
            public Map<String, Object> getParameters() {
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");

                Map<String, Object> properties = new HashMap<>();
                Map<String, Object> toActivateParam = new HashMap<>();
                toActivateParam.put("type", "array");

                Map<String, Object> items = new HashMap<>();
                items.put("type", "string");

                // Only META-scoped groups appear in the enum
                List<String> availableGroups = new ArrayList<>(groupManager.getMetaGroupNames());
                if (!availableGroups.isEmpty()) {
                    items.put("enum", availableGroups);
                }

                toActivateParam.put("items", items);
                toActivateParam.put(
                        "description",
                        "The FINAL list of tool group names to keep active. "
                                + "Groups NOT in this list will be deactivated. "
                                + "Pass an empty list to deactivate all groups.");

                properties.put("to_activate", toActivateParam);
                schema.put("properties", properties);
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

    boolean isResetEquippedToolsAgentTool(AgentTool tool) {
        return tool instanceof ResetEquippedToolsAgentTool;
    }

    private abstract static class ResetEquippedToolsAgentTool implements AgentTool {}

    /**
     * Implementation of reset_equipped_tools logic.
     *
     * <p>Uses <b>replacement semantics</b>: all META-scoped groups not in the input list are
     * deactivated. EXTERNAL-scoped groups are unaffected.
     *
     * @param toActivate List of tool group names to activate (must all be META scope)
     * @return Response message describing the resulting state
     * @throws IllegalArgumentException if any group doesn't exist
     */
    private String resetEquippedToolsImpl(List<String> toActivate) {
        // Validate: all groups must exist and be META scope
        for (String groupName : toActivate) {
            groupManager.validateGroupExists(groupName);
            ToolGroup group = groupManager.getToolGroup(groupName);
            if (group.getScope() != ToolGroupScope.META) {
                return "Error: Group '" + groupName + "' is not manageable by this tool.";
            }
        }

        // Replace: deactivate all META groups, then activate specified ones
        groupManager.replaceMetaActiveGroups(toActivate);

        // Build response (aligned with Python format)
        if (toActivate.isEmpty()) {
            return "All tool groups are currently deactivated.";
        }

        String groupNames = toActivate.stream().collect(Collectors.joining(", "));
        StringBuilder result = new StringBuilder();
        result.append("The currently activated tool group(s): ").append(groupNames).append(".\n");

        // Collect groups that have descriptions for tool-instructions block
        List<ToolGroup> activatedGroups = new ArrayList<>();
        for (String groupName : toActivate) {
            ToolGroup group = groupManager.getToolGroup(groupName);
            if (group != null) {
                activatedGroups.add(group);
            }
        }

        boolean hasInstructions =
                activatedGroups.stream()
                        .anyMatch(g -> g.getDescription() != null && !g.getDescription().isEmpty());
        if (hasInstructions) {
            result.append("<tool-instructions>\n");
            for (ToolGroup group : activatedGroups) {
                if (group.getDescription() != null && !group.getDescription().isEmpty()) {
                    result.append(
                            String.format(
                                    "<group name=\"%s\">%s</group>\n",
                                    group.getName(), group.getDescription()));
                }
            }
            result.append("</tool-instructions>");
        }

        return result.toString();
    }
}
