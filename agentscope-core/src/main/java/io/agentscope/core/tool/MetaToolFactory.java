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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

                    String result = resetEquippedToolsImpl(toActivate, resolveToolContext(param));
                    return Mono.just(ToolResultBlock.text(result));
                } catch (Exception e) {
                    return Mono.just(ToolResultBlock.error(e.getMessage()));
                }
            }
        };
    }

    /**
     * Resolves the per-call {@link ToolContextState} from the tool call's runtime context, so the
     * meta tool targets the active session's activation set instead of the shared group manager.
     *
     * @param param the tool call parameters
     * @return the resolved tool context state, or {@code null} when unavailable
     */
    private ToolContextState resolveToolContext(ToolCallParam param) {
        RuntimeContext rc = param.getRuntimeContext();
        AgentState state = rc != null ? rc.getAgentState() : null;
        return state != null ? state.getToolContext() : null;
    }

    /**
     * Implementation of reset_equipped_tools logic.
     *
     * <p>Uses <b>replacement semantics</b>: all META-scoped groups not in the input list are
     * deactivated. EXTERNAL-scoped groups are unaffected. The activation set is always written to
     * the per-call {@link ToolContextState} (the single source of truth for the session); the
     * shared group manager is never mutated. When no per-session context is available the tool
     * fails fast without touching shared state.
     *
     * @param toActivate List of tool group names to activate (must all be META scope)
     * @param tcs the per-call tool context state (must be non-null)
     * @return Response message describing the resulting state
     * @throws IllegalArgumentException if any group doesn't exist
     */
    private String resetEquippedToolsImpl(List<String> toActivate, ToolContextState tcs) {
        // Validate: all groups must exist and be META scope
        for (String groupName : toActivate) {
            groupManager.validateGroupExists(groupName);
            ToolGroup group = groupManager.getToolGroup(groupName);
            if (group.getScope() != ToolGroupScope.META) {
                return "Error: Group '" + groupName + "' is not manageable by this tool.";
            }
        }

        // No per-session context: never fall back to mutating the shared group manager. A missing
        // runtime context is an integration fault, and touching shared state here would be
        // destructive — fail fast instead.
        if (tcs == null) {
            return "Error: reset_equipped_tools requires a per-session runtime context";
        }

        // Single source of truth: recompute the session's activation set without touching the
        // shared group manager. Keep EXTERNAL-scoped groups, replace META-scoped ones.
        Set<String> metaGroupNames = groupManager.getMetaGroupNames();
        List<String> nextActivatedGroups = new ArrayList<>();
        for (String g : tcs.getActivatedGroups()) {
            if (!metaGroupNames.contains(g)) {
                nextActivatedGroups.add(g);
            }
        }
        for (String g : toActivate) {
            if (!nextActivatedGroups.contains(g)) {
                nextActivatedGroups.add(g);
            }
        }
        tcs.setActivatedGroups(nextActivatedGroups);

        // Build response (aligned with Python format)
        if (toActivate.isEmpty()) {
            // Only META groups are deactivated here; EXTERNAL-scoped groups are preserved.
            return "All META tool groups are currently deactivated.";
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
