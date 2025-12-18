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

/**
 * Metadata wrapper for registered skills.
 *
 * <p>Records skill registration information including skill ID, group membership,
 * and activation state. The activation state determines whether the skill's
 * associated tools are available to the LLM.
 */
public class RegisteredSkill {
    private final String skillId;
    private final String groupName; // null for ungrouped tools
    private boolean active; // whether this skill is being used by llm, if using need activate the

    /**
     * Creates a registered skill.
     *
     * @param skillId The skill ID
     * @param groupName The group name (null for ungrouped skills)
     */
    public RegisteredSkill(String skillId, String groupName) {
        this.skillId = skillId;
        this.groupName = groupName;
        this.active = false;
    }

    /**
     * Sets the activation state.
     *
     * @param active Whether to activate the skill
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Gets the activation state.
     *
     * @return true if active, false otherwise
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Gets the skill ID.
     *
     * @return The skill ID
     */
    public String getSkillId() {
        return skillId;
    }

    /**
     * Gets the group name.
     *
     * @return The group name (null for ungrouped skills)
     */
    public String getGroupName() {
        return groupName;
    }

    /**
     * Gets the tool group name for this skill.
     *
     * @return The tool group name
     */
    public String getToolsGroupName() {
        return skillId + "_skill_tools";
    }
}
