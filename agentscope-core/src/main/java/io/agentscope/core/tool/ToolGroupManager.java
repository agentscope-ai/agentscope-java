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

    private final Map<String, ToolGroup> toolGroups = new ConcurrentHashMap<>();
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
            toolsToRemove.addAll(group.getTools());

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
     * Check if a tool is in an active group.
     *
     * @param groupName Group name (can be null for ungrouped tools)
     * @return true if ungrouped or in active group
     */
    public boolean isInActiveGroup(String groupName) {
        if (groupName == null) {
            return true; // Ungrouped tools are always active
        }

        ToolGroup group = toolGroups.get(groupName);
        return group != null && group.isActive();
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
}
