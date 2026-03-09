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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages tool groups and their activation states.
 */
class ToolGroupManager {

    private static final Logger logger = LoggerFactory.getLogger(ToolGroupManager.class);

    private final Map<String, ToolGroup> toolGroups = new ConcurrentHashMap<>(); // group -> tools
    private final Map<String, Set<String>> tools = new ConcurrentHashMap<>(); // tool -> groups
    private List<String> activeGroups = new ArrayList<>();

    /**
     * Create tool groups and record them in the manager.
     *
     * @param groupName Name of the tool group
     * @param description Description of the tool group for the agent to understand
     * @param active Whether the tool group is active by default
     * @throws IllegalArgumentException if group already exists
     */
    public void createToolGroup(String groupName, String description, boolean active) {
        if (toolGroups.containsKey(groupName)) {
            throw new IllegalArgumentException(
                    String.format("Tool group '%s' already exists", groupName));
        }

        ToolGroup group =
                ToolGroup.builder().name(groupName).description(description).active(active).build();

        toolGroups.put(groupName, group);

        if (active && !activeGroups.contains(groupName)) {
            activeGroups.add(groupName);
        }

        logger.info("Created tool group '{}': {}", groupName, description);
    }

    /**
     * Creates a tool group with default active status (true).
     *
     * @param groupName Name of the tool group
     * @param description Description of the tool group for the agent to understand
     * @throws IllegalArgumentException if group already exists
     */
    public void createToolGroup(String groupName, String description) {
        createToolGroup(groupName, description, true);
    }

    /**
     * Update the active status of tool groups.
     *
     * @param groupNames List of tool group names to update
     * @param active Whether to activate or deactivate
     * @throws IllegalArgumentException if any group doesn't exist
     */
    public void updateToolGroups(List<String> groupNames, boolean active) {
        for (String groupName : groupNames) {
            ToolGroup group = toolGroups.get(groupName);
            if (group == null) {
                throw new IllegalArgumentException(
                        String.format("Tool group '%s' does not exist", groupName));
            }

            group.setActive(active);

            if (active) {
                if (!activeGroups.contains(groupName)) {
                    activeGroups.add(groupName);
                }
            } else {
                activeGroups.remove(groupName);
            }

            logger.info("Tool group '{}' active status set to: {}", groupName, active);
        }
    }

    /**
     * Remove tool groups.
     * Note: Caller is responsible for removing associated tools.
     *
     * @param groupNames List of tool group names to remove
     * @return Set of tool names that were in the removed groups
     */
    public Set<String> removeToolGroups(List<String> groupNames) {
        Set<String> toolsToRemove = new HashSet<>();

        for (String groupName : groupNames) {
            ToolGroup group = toolGroups.remove(groupName);
            if (group == null) {
                logger.warn("Tool group '{}' does not exist, skipping removal", groupName);
                continue;
            }

            // Collect tools from this group
            Set<String> groupTools = group.getTools();

            // Remove group mapping from tool index
            for (String toolName : groupTools) {
                if (removeGroupFromToolIndex(toolName, groupName)) {
                    toolsToRemove.add(toolName);
                }
            }

            // Remove from active groups
            activeGroups.remove(groupName);

            logger.info(
                    "Removed tool group '{}' with {} tools", groupName, group.getTools().size());
        }

        return toolsToRemove;
    }

    /**
     * Get notes about activated tool groups for display to user/agent.
     *
     * @return Formatted string describing active tool groups
     */
    public String getActivatedNotes() {
        if (activeGroups.isEmpty()) {
            return "No tool groups are currently activated.";
        }

        StringBuilder notes = new StringBuilder("Activated tool groups:\n");
        for (String groupName : activeGroups) {
            ToolGroup group = toolGroups.get(groupName);
            if (group != null) {
                notes.append(String.format("- %s: %s\n", groupName, group.getDescription()));
            }
        }
        return notes.toString();
    }

    /**
     * Get notes about all tool groups for display to user/agent.
     *
     * @return Formatted string describing active tool groups
     */
    public String getNotes() {
        StringBuilder activatedNotes = new StringBuilder("Activated tool groups:\n");
        StringBuilder inactiveNotes = new StringBuilder("Inactive tool groups:\n");
        boolean hasActivatedGroup = false;
        boolean hasInactiveGroup = false;
        for (ToolGroup group : toolGroups.values()) {
            if (group.isActive()) {
                hasActivatedGroup = true;
                activatedNotes.append(
                        String.format("- %s: %s\n", group.getName(), group.getDescription()));
            } else {
                hasInactiveGroup = true;
                inactiveNotes.append(
                        String.format("- %s: %s\n", group.getName(), group.getDescription()));
            }
        }

        if (!hasActivatedGroup) {
            activatedNotes.append("No tool groups are currently activated.\n");
        }

        if (!hasInactiveGroup) {
            inactiveNotes.append("No tool groups are currently inactive.\n");
        }

        activatedNotes.append(inactiveNotes);
        return activatedNotes.toString();
    }

    /**
     * Validate that a group exists.
     *
     * @param groupName Group name to validate
     * @throws IllegalArgumentException if group doesn't exist
     */
    public void validateGroupExists(String groupName) {
        if (!toolGroups.containsKey(groupName)) {
            throw new IllegalArgumentException(
                    String.format("Tool group '%s' does not exist", groupName));
        }
    }

    /**
     * Check if a group is active.
     *
     * @param groupName Group name
     * @return true if the group exists and is active
     */
    public boolean isActiveGroup(String groupName) {
        if (groupName == null) {
            return false;
        }
        ToolGroup group = toolGroups.get(groupName);
        return group != null && group.isActive();
    }

    /**
     * Check if a tool is in any active group.
     *
     * <p>If the tool is not in any group, it is considered active by default.
     *
     * @param toolName Tool name
     * @return true if ungrouped or in at least one active group
     */
    public boolean isActiveTool(String toolName) {
        if (toolName == null) {
            return false;
        }
        Set<String> groups = tools.get(toolName);
        if (groups == null || groups.isEmpty()) {
            return true;
        }
        for (String groupName : groups) {
            if (isActiveGroup(groupName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check whether a tool belongs to any group.
     *
     * @param toolName Tool name
     * @return true if the tool is in at least one group
     */
    public boolean isGroupedTool(String toolName) {
        if (toolName == null) {
            return false;
        }
        Set<String> groups = tools.get(toolName);
        return groups != null && !groups.isEmpty();
    }

    /**
     * Get all tools that belong to active groups.
     *
     * @return Set of tool names that are in active groups
     */
    public Set<String> getActiveToolNames() {
        Set<String> activeTools = new HashSet<>();
        for (ToolGroup group : toolGroups.values()) {
            if (group.isActive()) {
                activeTools.addAll(group.getTools());
            }
        }
        return activeTools;
    }

    /**
     * Add a tool to a group.
     *
     * @param groupName Group name
     * @param toolName Tool name
     */
    public void addToolToGroup(String groupName, String toolName) {
        ToolGroup group = toolGroups.get(groupName);
        if (group != null) {
            group.addTool(toolName);
            tools.computeIfAbsent(toolName, key -> ConcurrentHashMap.newKeySet()).add(groupName);
        }
    }

    /**
     * Remove a tool from a group.
     *
     * @param groupName Group name
     * @param toolName Tool name
     */
    public void removeToolFromGroup(String groupName, String toolName) {
        ToolGroup group = toolGroups.get(groupName);
        if (group != null) {
            group.removeTool(toolName);
            removeGroupFromToolIndex(toolName, groupName);
        }
    }

    /**
     * Get all tool group names.
     *
     * @return Set of all tool group names
     */
    public Set<String> getToolGroupNames() {
        return new HashSet<>(toolGroups.keySet());
    }

    /**
     * Get active tool group names.
     *
     * @return List of active group names
     */
    public List<String> getActiveGroups() {
        return new ArrayList<>(activeGroups);
    }

    /**
     * Set active groups (for state restoration).
     *
     * @param activeGroups List of group names to mark as active
     */
    public void setActiveGroups(List<String> activeGroups) {
        this.activeGroups = new ArrayList<>(activeGroups);

        // Mark corresponding groups as active
        for (String groupName : activeGroups) {
            ToolGroup group = toolGroups.get(groupName);
            if (group != null) {
                group.setActive(true);
            }
        }
    }

    /**
     * Get a tool group by name.
     *
     * @param groupName Name of the tool group
     * @return ToolGroup or null if not found
     */
    public ToolGroup getToolGroup(String groupName) {
        return toolGroups.get(groupName);
    }

    /**
     * Copy all tool groups from this manager to another manager.
     *
     * @param target The target manager to copy tool groups to
     */
    void copyTo(ToolGroupManager target) {
        target.tools.clear();
        target.toolGroups.clear();
        for (Map.Entry<String, ToolGroup> entry : toolGroups.entrySet()) {
            String groupName = entry.getKey();
            ToolGroup sourceGroup = entry.getValue();

            // Create a copy of the tool group
            ToolGroup copiedGroup =
                    ToolGroup.builder()
                            .name(groupName)
                            .description(sourceGroup.getDescription())
                            .active(sourceGroup.isActive())
                            .tools(sourceGroup.getTools())
                            .build();

            target.toolGroups.put(groupName, copiedGroup);
        }

        for (Map.Entry<String, Set<String>> entry : tools.entrySet()) {
            target.tools.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }

        // Copy activeGroups list
        target.activeGroups = new ArrayList<>(this.activeGroups);
    }

    private boolean removeGroupFromToolIndex(String toolName, String groupName) {
        Set<String> groupNames = tools.get(toolName);
        if (groupNames == null) {
            return false;
        }
        groupNames.remove(groupName);
        if (groupNames.isEmpty()) {
            tools.remove(toolName);
            return true;
        }
        return false;
    }
}
