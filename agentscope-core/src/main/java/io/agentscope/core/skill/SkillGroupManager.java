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
package io.agentscope.core.skill;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manager for skill group creation, activation, and lifecycle management.
 *
 * <p>Provides skill group lifecycle management capabilities including:
 * <ul>
 *   <li>Creating and removing skill groups
 *   <li>Activating and deactivating skill groups
 *   <li>Managing skill-to-group associations
 *   <li>Querying activation states and group information
 * </ul>
 *
 * <p><b>Usage example:</b>
 * <pre>{@code
 * SkillGroupManager manager = new SkillGroupManager();
 * manager.createSkillGroup("data_analysis", "Data analysis skill group", true);
 * manager.addSkillToGroup("data_analysis", "calculate");
 * }</pre>
 */
public class SkillGroupManager {
    private static final Logger logger = LoggerFactory.getLogger(SkillGroupManager.class);

    private final Map<String, SkillGroup> skillGroups = new ConcurrentHashMap<>();
    private List<String> activeGroups = new ArrayList<>();

    /**
     * Creates a skill group and registers it with the manager.
     *
     * @param groupName The skill group name
     * @param description The skill group description for agent understanding
     * @param active Whether to activate by default
     * @throws IllegalArgumentException if the skill group already exists
     */
    public void createSkillGroup(String groupName, String description, boolean active) {
        if (skillGroups.containsKey(groupName)) {
            throw new IllegalArgumentException(
                    String.format("Skill group '%s' already exists", groupName));
        }

        SkillGroup group =
                SkillGroup.builder()
                        .name(groupName)
                        .description(description)
                        .active(active)
                        .build();

        skillGroups.put(groupName, group);

        if (active && !activeGroups.contains(groupName)) {
            activeGroups.add(groupName);
        }

        logger.info("Created skill group '{}': {}", groupName, description);
    }

    /**
     * Creates a skill group (activated by default).
     *
     * @param groupName The skill group name
     * @param description The skill group description for agent understanding
     * @throws IllegalArgumentException if the skill group already exists
     */
    public void createSkillGroup(String groupName, String description) {
        createSkillGroup(groupName, description, true);
    }

    /**
     * Updates the activation state of skill groups.
     *
     * @param groupNames The list of skill group names to update
     * @param active Whether to activate the groups
     * @throws IllegalArgumentException if any skill group does not exist
     */
    public void updateSkillGroups(List<String> groupNames, boolean active) {
        for (String groupName : groupNames) {
            SkillGroup group = skillGroups.get(groupName);
            if (group == null) {
                throw new IllegalArgumentException(
                        String.format("Skill group '%s' does not exist", groupName));
            }

            group.setActive(active);

            if (active) {
                if (!activeGroups.contains(groupName)) {
                    activeGroups.add(groupName);
                }
            } else {
                activeGroups.remove(groupName);
            }

            logger.info("Skill group '{}' active status set to: {}", groupName, active);
        }
    }

    /**
     * Removes skill groups.
     *
     * <p><b>Note:</b> The caller is responsible for removing associated skills.
     *
     * @param groupNames The list of skill group names to remove
     * @return Set of skill names contained in the removed groups
     */
    public Set<String> removeSkillGroups(List<String> groupNames) {
        Set<String> skillsToRemove = new HashSet<>();

        for (String groupName : groupNames) {
            SkillGroup group = skillGroups.remove(groupName);
            if (group == null) {
                logger.warn("Skill group '{}' does not exist, skipping removal", groupName);
                continue;
            }

            // Collect skills from this group
            skillsToRemove.addAll(group.getSkills());

            // Remove from active groups
            activeGroups.remove(groupName);

            logger.info(
                    "Removed skill group '{}' with {} skills", groupName, group.getSkills().size());
        }

        return skillsToRemove;
    }

    /**
     * Gets notes about activated skill groups.
     *
     * @return Formatted string describing activated skill groups
     */
    public String getActivatedNotes() {
        if (activeGroups.isEmpty()) {
            return "No skill groups are currently activated.";
        }

        StringBuilder notes = new StringBuilder("Activated skill groups:\n");
        for (String groupName : activeGroups) {
            SkillGroup group = skillGroups.get(groupName);
            if (group != null) {
                notes.append(String.format("- %s: %s\n", groupName, group.getDescription()));
            }
        }
        return notes.toString();
    }

    /**
     * Validates that a skill group exists.
     *
     * @param groupName The group name to validate
     * @throws IllegalArgumentException if the skill group does not exist
     */
    public void validateGroupExists(String groupName) {
        if (!skillGroups.containsKey(groupName)) {
            throw new IllegalArgumentException(
                    String.format("Skill group '%s' does not exist", groupName));
        }
    }

    /**
     * Checks if a skill is in an active group.
     *
     * @param groupName The group name (null for ungrouped skills)
     * @return true if ungrouped or in an active group
     */
    public boolean isInActiveGroup(String groupName) {
        if (groupName == null) {
            return true; // Ungrouped skills are always active
        }

        SkillGroup group = skillGroups.get(groupName);
        return group != null && group.isActive();
    }

    /**
     * Add a skill to a group.
     *
     * @param groupName Group name
     * @param skillName Skill name
     */
    public void addSkillToGroup(String groupName, String skillName) {
        SkillGroup group = skillGroups.get(groupName);
        if (group != null) {
            group.addSkill(skillName);
        }
    }

    /**
     * Remove a skill from a group.
     *
     * @param groupName Group name
     * @param skillName Skill name
     */
    public void removeSkillFromGroup(String groupName, String skillName) {
        SkillGroup group = skillGroups.get(groupName);
        if (group != null) {
            group.removeSkill(skillName);
        }
    }

    /**
     * Get all skill group names.
     *
     * @return Set of all skill group names
     */
    public Set<String> getSkillGroupNames() {
        return new HashSet<>(skillGroups.keySet());
    }

    /**
     * Get active skill group names.
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
            SkillGroup group = skillGroups.get(groupName);
            if (group != null) {
                group.setActive(true);
            }
        }
    }

    /**
     * Get a skill group by name.
     *
     * @param groupName Name of the skill group
     * @return SkillGroup or null if not found
     */
    public SkillGroup getSkillGroup(String groupName) {
        return skillGroups.get(groupName);
    }
}
